# Research
[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fizzzgoy%2Fevent-thread-core%2Fmaven-metadata.xml&label=maven-central)](https://central.sonatype.com/artifact/io.github.izzzgoy/event-thread-core)

## Description 


Here is a solution for declarative application description. It allows describing the desired logic of application behavior using configuration tools.

This toolkit enables the description of the application's operation on all necessary levels, from working with the graphical interface to interacting with data in the cache.

## Key concepts:
Scope - an entity that describes a certain part of the graphical interface (screen or individual widget) and contains all the necessary configuration to ensure its operation.

Resource - an object that is required for the execution of certain logic. It is created only when demanded and releases resources after the logic is executed.

> At the moment, there are two types of resources:
>
> Basic - contains only a stored object.
> 
> Observable - provides a StateFlow of objects of the specified type.

Containers - contain an abstraction over certain data, their state, and the logic of interacting with them. They include StateFlow, which allows building containers on top of observable resources. If resources abstract over some data, then the container describes methods for interacting with this data. Containers can also undergo concatenation, allowing the use of one or more containers to compose the final state.

Event Thread or Thread - entity, that can handle events. Contains a list of event handlers that can be triggered when using a special event.

## Installation


```kotlin
commonMain {
    dependencies {
        implementation("io.github.izzgoy:event-thread-core:$event_thread_version")
        implementation("io.github.izzgoy:event-thread-compose:$event_thread_version")
        implementation("io.github.izzgoy:event-thread-network:$event_thread_version")
        implementation("io.github.izzgoy:event-thread-cache:$event_thread_version")
        //available only on android/ios target
        //KValut under hud
        implementation("io.github.izzgoy:event-thread-secure:$event_thread_version")
    }
}
```

***