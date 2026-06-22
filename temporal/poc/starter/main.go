// Command starter executes one GreetingWorkflow and prints the result.
package main

import (
	"context"
	"log"

	"github.com/koshereats/temporal/poc"
	"go.temporal.io/sdk/client"
)

func main() {
	c, err := client.Dial(client.Options{HostPort: "127.0.0.1:7233"})
	if err != nil {
		log.Fatalln("dial:", err)
	}
	defer c.Close()

	we, err := c.ExecuteWorkflow(context.Background(),
		client.StartWorkflowOptions{ID: "poc-greeting-1", TaskQueue: poc.TaskQueue},
		poc.GreetingWorkflow, "Salto")
	if err != nil {
		log.Fatalln("execute:", err)
	}
	log.Printf("started workflow id=%s run=%s", we.GetID(), we.GetRunID())

	var result string
	if err := we.Get(context.Background(), &result); err != nil {
		log.Fatalln("result:", err)
	}
	log.Printf("RESULT: %q", result)
}
