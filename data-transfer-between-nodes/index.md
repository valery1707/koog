## Overview

Koog provides a way to store and pass data using `AIAgentStorage`, which is a key-value storage system designed as a type-safe way to pass data between different nodes or even subgraphs.

The storage is accessible through the `storage` property (`storage: AIAgentStorage`) available in agent nodes, allowing for seamless data sharing across different components of your AI agent system.

## Key and value structure

The key-value data storage structure relies on the `AIAgentStorageKey` data class. For more information about `AIAgentStorageKey`, see the sections below.

### AIAgentStorageKey

The storage uses a typed key system to ensure type safety when storing and retrieving data:

- `AIAgentStorageKey<T>`: A data class that represents a storage key used for identifying and accessing data. Here are the key features of the `AIAgentStorageKey` class:
  - The generic type parameter `T` specifies the type of data associated with this key, ensuring type safety.
  - Each key has a `name` property which is a string identifier that uniquely represents the storage key.

## Usage examples

The following sections provide an actual example of creating a storage key and using it to store and retrieve data.

### Defining a class that represents your data

The first step in storing data that you want to pass is creating a class that represents your data. Here is an example of a simple class with basic user data:

```kotlin
class UserData(
   val name: String,
   val age: Int
)
```

Once defined, use the class to create a storage key as described below.

### Creating a storage key

Create a typed storage key for the defined data structure:

```kotlin
val userDataKey = createStorageKey<UserData>("user-data")
```

The `createStorageKey` function takes a single string parameter that uniquely identifies the key.

### Storing data

To save data using a created storage key, use the `storage.set(key: AIAgentStorageKey<T>, value: T)` method in a node:

```kotlin
val nodeSaveData by node<Unit, Unit> {
    storage.set(userDataKey, UserData("John", 26))
}
```

### Retrieving data

To retrieve the data, use the `storage.get` method in a node:

```kotlin
val nodeRetrieveData by node<String, Unit> { message ->
    storage.get(userDataKey)?.let { userFromStorage ->
        println("Hello dear $userFromStorage, here's a message for you: $message")
    }
}
```

## API documentation

For a complete reference related to the `AIAgentStorage` class, see [AIAgentStorage](https://api.koog.ai/agents/agents-core/ai.koog.agents.core.agent.entity/-a-i-agent-storage/index.html).

For individual functions available in the `AIAgentStorage` class, see the following API references:

- [clear](https://api.koog.ai/agents/agents-core/ai.koog.agents.core.agent.entity/-a-i-agent-storage/clear.html)
- [get](https://api.koog.ai/agents/agents-core/ai.koog.agents.core.agent.entity/-a-i-agent-storage/get.html)
- [getValue](https://api.koog.ai/agents/agents-core/ai.koog.agents.core.agent.entity/-a-i-agent-storage/get-value.html)
- [putAll](https://api.koog.ai/agents/agents-core/ai.koog.agents.core.agent.entity/-a-i-agent-storage/put-all.html)
- [remove](https://api.koog.ai/agents/agents-core/ai.koog.agents.core.agent.entity/-a-i-agent-storage/remove.html)
- [set](https://api.koog.ai/agents/agents-core/ai.koog.agents.core.agent.entity/-a-i-agent-storage/set.html)
- [toMap](https://api.koog.ai/agents/agents-core/ai.koog.agents.core.agent.entity/-a-i-agent-storage/to-map.html)

## Additional information

- `AIAgentStorage` is thread-safe, using a Mutex to ensure concurrent access is handled properly.
- The storage is designed to work with any type that extends `Any`.
- When retrieving values, type casting is handled automatically, ensuring type safety throughout your application.
- For non-nullable access to values, use the `getValue` method which throws an exception if the key does not exist.
- You can clear the storage entirely using the `clear` method, which removes all stored key-value pairs.
