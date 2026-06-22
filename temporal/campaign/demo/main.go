// Command demo runs a 5-round BugFixCampaign through Temporal with an
// evaluation after round 3 that changes the direction for rounds 4-5. The
// round/eval commands are cheap shell stand-ins for the agent orchestration.
package main

import (
	"context"
	"log"

	"github.com/koshereats/temporal/campaign"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/worker"
)

func main() {
	c, err := client.Dial(client.Options{HostPort: "127.0.0.1:7233"})
	if err != nil {
		log.Fatalln("dial:", err)
	}
	defer c.Close()

	w := worker.New(c, campaign.TaskQueue, worker.Options{})
	w.RegisterWorkflow(campaign.BugFixCampaignWorkflow)
	w.RegisterActivity(campaign.RunRound)
	w.RegisterActivity(campaign.EvaluateCodebase)
	if err := w.Start(); err != nil {
		log.Fatalln("worker start:", err)
	}
	defer w.Stop()

	cfg := campaign.CampaignConfig{
		Rounds:           5,
		EvaluateAfter:    3,
		InitialDirection: "broad",
		// $1=round $2=direction. Stand-in for `claude -p "<round prompt>"`.
		RoundCmd: `echo "round $1 ($2 sweep) FIXES=$(( ($1 * 2) + 5 ))"`,
		// $1=fixesSoFar. Stand-in for the evaluation workflow; prints the new direction.
		EvalCmd: `echo "money-and-decoding-focus"`,
	}

	we, err := c.ExecuteWorkflow(context.Background(),
		client.StartWorkflowOptions{ID: "bugfix-campaign-1", TaskQueue: campaign.TaskQueue},
		campaign.BugFixCampaignWorkflow, cfg)
	if err != nil {
		log.Fatalln("execute:", err)
	}
	log.Printf("campaign started id=%s run=%s", we.GetID(), we.GetRunID())

	var res campaign.CampaignResult
	if err := we.Get(context.Background(), &res); err != nil {
		log.Fatalln("result:", err)
	}
	for _, r := range res.Rounds {
		log.Printf("  round %d: %d fixes  | %q", r.Round, r.FixCount, r.Output)
	}
	log.Printf("direction after eval: %q", res.FinalDirNew)
	log.Printf("TOTAL fixes across campaign: %d", res.TotalFixes)
	if len(res.Rounds) == 5 && res.FinalDirNew == "money-and-decoding-focus" {
		log.Println("PASS ✅ — 5 rounds sequenced durably; evaluation after round 3 re-steered 4-5; visible in UI :8233.")
	} else {
		log.Println("FAIL ❌")
	}
}
