// Package poc is a minimal Temporal proof-of-concept: one workflow that
// orchestrates two activities. Proves the toolchain end-to-end (server +
// worker registration + workflow execution + result), nothing app-specific.
package poc

import (
	"context"
	"fmt"
	"strings"
	"time"

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/workflow"
)

const TaskQueue = "poc-task-queue"

// GreetingWorkflow composes a greeting then shouts it — two sequential activities.
func GreetingWorkflow(ctx workflow.Context, name string) (string, error) {
	opts := workflow.ActivityOptions{StartToCloseTimeout: 10 * time.Second}
	ctx = workflow.WithActivityOptions(ctx, opts)

	var greeting string
	if err := workflow.ExecuteActivity(ctx, ComposeGreeting, name).Get(ctx, &greeting); err != nil {
		return "", err
	}
	var shouted string
	if err := workflow.ExecuteActivity(ctx, Shout, greeting).Get(ctx, &shouted); err != nil {
		return "", err
	}
	return shouted, nil
}

func ComposeGreeting(ctx context.Context, name string) (string, error) {
	activity.GetLogger(ctx).Info("ComposeGreeting", "name", name)
	return fmt.Sprintf("Hello, %s!", name), nil
}

func Shout(ctx context.Context, s string) (string, error) {
	activity.GetLogger(ctx).Info("Shout", "input", s)
	return strings.ToUpper(s), nil
}
