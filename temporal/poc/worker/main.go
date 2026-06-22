// Command worker runs the PoC Temporal worker: connects to the dev server,
// registers the workflow + activities on the task queue, and polls.
package main

import (
	"log"

	"github.com/koshereats/temporal/poc"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/worker"
)

func main() {
	c, err := client.Dial(client.Options{HostPort: "127.0.0.1:7233"})
	if err != nil {
		log.Fatalln("dial:", err)
	}
	defer c.Close()

	w := worker.New(c, poc.TaskQueue, worker.Options{})
	w.RegisterWorkflow(poc.GreetingWorkflow)
	w.RegisterActivity(poc.ComposeGreeting)
	w.RegisterActivity(poc.Shout)

	log.Println("poc worker started on", poc.TaskQueue)
	if err := w.Run(worker.InterruptCh()); err != nil {
		log.Fatalln("worker:", err)
	}
}
