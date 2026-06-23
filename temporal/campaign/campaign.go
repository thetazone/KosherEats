// Package campaign is a LOCAL Temporal implementation of the multi-round
// bug-fix cycle (Use #3). It demonstrates the orchestration SHAPE that the
// Workflow-tool cycle ran ad-hoc: rounds 1..N in sequence, a codebase
// evaluation after round K that re-sets the direction for the remaining rounds,
// and accumulated results — but now as a durable, resumable, UI-visible
// Temporal workflow (a worker crash mid-campaign resumes from the last
// completed round instead of restarting).
//
// The RunRound / EvaluateCodebase activities REALLY shell out to an external
// process, proving the bridge. In production that command would invoke the
// agent orchestration (e.g. `claude -p "<round prompt>"`); the demo points it
// at cheap commands so no expensive agents run.
package campaign

import (
	"context"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/workflow"
)

const TaskQueue = "campaign-task-queue"

type CampaignConfig struct {
	Rounds           int
	EvaluateAfter    int    // run EvaluateCodebase after this round (0 = never)
	InitialDirection string // bash command template for a round; %d = round, %s = direction
	RoundCmd         string // command run per round (receives round + direction as args)
	EvalCmd          string // command run for the evaluation step
}

type RoundResult struct {
	Round    int
	FixCount int
	Output   string
}

type CampaignResult struct {
	Rounds      []RoundResult
	TotalFixes  int
	FinalDirNew string
}

// BugFixCampaignWorkflow sequences the rounds and the mid-cycle evaluation.
func BugFixCampaignWorkflow(ctx workflow.Context, cfg CampaignConfig) (CampaignResult, error) {
	ctx = workflow.WithActivityOptions(ctx, workflow.ActivityOptions{
		StartToCloseTimeout: 2 * time.Minute,
		RetryPolicy:         &temporal.RetryPolicy{MaximumAttempts: 3},
	})

	var out CampaignResult
	direction := cfg.InitialDirection
	for round := 1; round <= cfg.Rounds; round++ {
		var rr RoundResult
		if err := workflow.ExecuteActivity(ctx, RunRound, cfg.RoundCmd, round, direction).Get(ctx, &rr); err != nil {
			return out, err
		}
		out.Rounds = append(out.Rounds, rr)
		out.TotalFixes += rr.FixCount

		if cfg.EvaluateAfter > 0 && round == cfg.EvaluateAfter {
			var newDir string
			if err := workflow.ExecuteActivity(ctx, EvaluateCodebase, cfg.EvalCmd, out.TotalFixes).Get(ctx, &newDir); err != nil {
				return out, err
			}
			direction = newDir
			out.FinalDirNew = newDir
		}
	}
	return out, nil
}

// RunRound shells out to the round command and parses "FIXES=<n>" from stdout.
func RunRound(ctx context.Context, cmdTmpl string, round int, direction string) (RoundResult, error) {
	activity.GetLogger(ctx).Info("RunRound", "round", round, "direction", direction)
	cmd := exec.CommandContext(ctx, "bash", "-c", cmdTmpl,
		"bash", strconv.Itoa(round), direction)
	b, err := cmd.CombinedOutput()
	if err != nil {
		return RoundResult{}, err
	}
	out := strings.TrimSpace(string(b))
	return RoundResult{Round: round, FixCount: parseFixes(out), Output: out}, nil
}

// EvaluateCodebase shells out to the eval command; its stdout is the new direction.
func EvaluateCodebase(ctx context.Context, cmdTmpl string, fixesSoFar int) (string, error) {
	activity.GetLogger(ctx).Info("EvaluateCodebase", "fixesSoFar", fixesSoFar)
	cmd := exec.CommandContext(ctx, "bash", "-c", cmdTmpl, "bash", strconv.Itoa(fixesSoFar))
	b, err := cmd.CombinedOutput()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(b)), nil
}

// parseFixes pulls the integer after "FIXES=" if present (else 0).
func parseFixes(s string) int {
	for _, tok := range strings.Fields(s) {
		if strings.HasPrefix(tok, "FIXES=") {
			if n, err := strconv.Atoi(strings.TrimPrefix(tok, "FIXES=")); err == nil {
				return n
			}
		}
	}
	return 0
}
