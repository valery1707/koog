# Agent Persistence

Agent Persistence is a feature that provides checkpoint functionality for AI agents in the Koog framework. It lets you save and restore the state of an agent at specific points during execution, enabling capabilities such as:

- Resuming agent execution from a specific point
- Rolling back to previous states
- Persisting agent state across sessions

## Key concepts

### Checkpoints

A checkpoint captures the complete state of an agent at a specific point in its execution, including:

- Message history (all interactions between user, system, assistant, and tools)
- Current node being executed
- Input data for the current node
- Timestamp of creation

Checkpoints are identified by unique IDs and are associated with a specific agent.

## Prerequisites

The Agent Persistence feature requires that all nodes in your agent's strategy have unique names. This is enforced when the feature is installed:

```kotlin
require(ctx.strategy.metadata.uniqueNames) {
    "Checkpoint feature requires unique node names in the strategy metadata"
}
```

Make sure to set unique names for nodes in your graph.

## Installation

To use the Agent Persistence feature, add it to your agent's configuration:

```kotlin
val agent = AIAgent(
    promptExecutor = executor,
    llmModel = OllamaModels.Meta.LLAMA_3_2,
) {
    install(Persistence) {
        // Use in-memory storage for snapshots
        storage = InMemoryPersistenceStorageProvider()
        // Enable automatic persistence
        enableAutomaticPersistence = true
    }
}
```

## Configuration options

The Agent Persistence feature has two main configuration options:

- **Storage provider**: the provider used to save and retrieve checkpoints.
- **Continuous persistence**: automatic creation of checkpoints after each node is run.

### Storage provider

Set the storage provider that will be used to save and retrieve checkpoints:

```kotlin
install(Persistence) {
    storage = InMemoryPersistenceStorageProvider()
}
```

The framework includes the following built-in providers:

- `InMemoryPersistenceStorageProvider`: stores checkpoints in memory (lost when the application restarts).
- `FilePersistenceStorageProvider`: persists checkpoints to the file system.
- `NoPersistenceStorageProvider`: a no-op implementation that does not store checkpoints. This is the default provider.

You can also implement custom storage providers by implementing the `PersistenceStorageProvider` interface. For more information, see [Custom storage providers](#custom-storage-providers).

### Continuous persistence

Continuous persistence means that a checkpoint is automatically created after each node is run. To activate continuous persistence, use the code below:

```kotlin
install(Persistence) {
    enableAutomaticPersistence = true
}
```

When activated, the agent will automatically create a checkpoint after each node is executed, allowing for fine-grained recovery.

## Basic usage

### Creating a checkpoint

To learn how to create a checkpoint at a specific point in your agent's execution, see the code sample below:

```kotlin
suspend fun example(context: AIAgentContext) {
    // Create a checkpoint with the current state
    val checkpoint = context.persistence().createCheckpoint(
        agentContext = context,
        nodeId = "current-node-id",
        lastInput = inputData,
        lastInputType = inputType,
        checkpointId = context.runId,
        version = 0L
    )

    // The checkpoint ID can be stored for later use
    val checkpointId = checkpoint?.checkpointId
}
```

### Restoring from a checkpoint

To restore the state of an agent from a specific checkpoint, follow the code sample below:

```kotlin
suspend fun example(context: AIAgentContext, checkpointId: String) {
    // Roll back to a specific checkpoint
    context.persistence().rollbackToCheckpoint(checkpointId, context)

    // Or roll back to the latest checkpoint
    context.persistence().rollbackToLatestCheckpoint(context)
}
```

#### Rolling back all side-effects produced by tools

It's quite common for some tools to produce side-effects. Specifically, when you are running your agents on the backend, some of the tools would likely perform some database transactions. This makes it much harder for your agent to travel back in time.

Imagine, that you have a tool `createUser` that creates a new user in your database. And your agent has populated multiple tool calls overtime:

```text
tool call: createUser "Alex"

->>>> checkpoint-1 <<<<-

tool call: createUser "Daniel"
tool call: createUser "Maria"
```

And now you would like to roll back to a checkpoint. Restoring the agent's state (including message history, and strategy graph node) alone would not be sufficient to achieve the exact state of the world before the checkpoint. You should also restore the side-effects produced by your tool calls. In our example, this would mean removing `Maria` and `Daniel` from the database.

With Koog Persistence you can achieve that by providing a `RollbackToolRegistry` to `Persistence` feature config:

```kotlin
install(Persistence) {
    enableAutomaticPersistence = true
    rollbackToolRegistry = RollbackToolRegistry {
        // For every `createUser` tool call there will be a `removeUser` invocation in the reverse order 
        // when rolling back to the desired execution point.
        // Note: `removeUser` tool should take the same exact arguments as `createUser`. 
        // It's the developer's responsibility to make sure that `removeUser` invocation rolls back all side-effects of `createUser`:
        registerRollback(::createUser, ::removeUser)
    }
}
```

### Using extension functions

The Agent Persistence feature provides convenient extension functions for working with checkpoints:

```kotlin
suspend fun example(context: AIAgentContext) {
    // Access the checkpoint feature
    val checkpointFeature = context.persistence()

    // Or perform an action with the checkpoint feature
    context.withPersistence { ctx ->
        // 'this' is the checkpoint feature
        createCheckpoint(
            agentContext = ctx,
            nodeId = "current-node-id",
            lastInput = inputData,
            lastInputType = inputType,
            checkpointId = ctx.runId,
            version = 0L
        )
    }
}
```

## Advanced usage

### Custom storage providers

You can implement custom storage providers by implementing the `PersistenceStorageProvider` interface:

```kotlin
class MyCustomStorageProvider<MyFilterType> : PersistenceStorageProvider<MyFilterType> {
    override suspend fun getCheckpoints(agentId: String, filter: MyFilterType?): List<AgentCheckpointData> {
        TODO("Not yet implemented")
    }

    override suspend fun saveCheckpoint(agentId: String, agentCheckpointData: AgentCheckpointData) {
        TODO("Not yet implemented")
    }

    override suspend fun getLatestCheckpoint(agentId: String, filter: MyFilterType?): AgentCheckpointData? {
        TODO("Not yet implemented")
    }
}
```

To use your custom provider in the feature configuration, set it as the storage when configuring the Agent Persistence feature in your agent.

```kotlin
install(Persistence) {
    storage = MyCustomStorageProvider<Any>()
}
```

### Setting execution points

For advanced control, you can directly set the execution point of an agent:

```kotlin
fun example(context: AIAgentContext) {
    context.persistence().setExecutionPoint(
        agentContext = context,
        nodeId = "target-node-id",
        messageHistory = customMessageHistory,
        input = customInput
    )
}
```

This allows for more fine-grained control over the agent's state beyond just restoring from checkpoints.
