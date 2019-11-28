![](https://github.com/bhowell2/dirwatcher/workflows/build/badge.svg)
[![codecov](https://codecov.io/gh/bhowell2/dirwatcher/branch/master/graph/badge.svg)](https://codecov.io/gh/bhowell2/dirwatcher)
![](https://img.shields.io/maven-central/v/io.github.bhowell2/dirwatcher)

# Directory Watcher
Provides common functionality for watching a directory for changes. Some features include: 
1. Registering callbacks for a directory for specific event kinds (e.g., only `ENTRY_CREATE`).
2. Recursively registering a callback for a directory and its subdirectories (and their subdirectories - ad infinitum) 
   as well as subdirectories created in the future for any of those.
3. Unregistering all callbacks for a directory or unregistering a specific callback for a directory. 

This only supports Java's `StandardWatchEventKinds`.

## Requirements
Requires Java 1.8+, because of lambda use. Has a single dependency of `SLF4J v1.7.28`.

## Install
This can be obtained from the Maven Central repository:

### Maven
```xml
<dependency>
    <groupId>io.github.bhowell2</groupId>
    <artifactId>dirwatcher</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle
```groovy
dependencies {
    compile "io.github.bhowell2:dirwatcher:0.1.0"
}
```

## Usage
This library is generally pretty simple, but there are some things that should be noted about the library and Java's 
`WatchService`. Normally it is only possible to register one `WatchKey` per `WatchService` per directory. The `WatchKey` 
is the same, regardless, of the event `Kind`(s) that are registered - this means that future `WatchKey` registrations 
of the same directory will override the event `Kind`(s) that trigger a detection - this may be viewed in some unit tests 
if desired. `DirWatcher` circumvents this limitation by providing callbacks to be called on an event change and tracks 
which `Kind` of events the callback should be called for. 

### Threading
The `DirWatcher` sets up a thread specifically for the `WatchService` to watch for changes. When a change occurs the 
callback(s) for the `WatchKey` will run on the `Executor` provided with the callback, or fallback to the `Executor` 
provided when creating the `DirWatcher`, or, finally, fallback to running on the `WatchService` thread. Running on the 
`WatchService` thread is not recommended as this could negatively impact watch performance.

### Events
As mentioned above this only supports Java's `StandardWatchEventKinds`. Any subset of those may be subscribed to 
for a callback and the callback will only be called for the specified events. Events are system dependent, so it 
is not guaranteed that creating a file will only trigger the `ENTRY_CREATE` event (it may also trigger `ENTRY_MODIFY` 
when creating a file.), so the user should keep this in mind. There is not a last event only implementation with 
this library as that is not a clear issue when dealing with the file system - if the user only cares about a change 
in the directory and not every change that is returned, they should debounce the callback. (I have created a 
[debouncer](https://github.com/bhowell2/debouncer) for java as well if you don't want to roll your own.)

### Subdirectories
In the case that the user wants to use the same callback for all changes (of the specified `Kind`) in all subdirectories 
of some given directory, they only need to set `recursive=true` - the callback will also be registered for all 
subdirectories created after the directory is registered as well as a subdirectory's subdirectories. 

```java
DirWatcher watcher = new DirWatcher(Executors.newSingleThreadExecutor());
watcher.registerDirectoryForAllKinds(Paths.get("/var/www/"), (dirPath, relativeFilePath, kind) -> {
  // do some stuff. will be executed on the executor supplied in the constructor
});

watcher.registerDirectoryForAllKinds(Paths.get("/var/www/"), 
false,  // recursive
Executors.newSingleThreadExecutor(),   // executor
(dirPath, relativeFilePath, kind) -> { 
  // do some stuff. will be executor on the executor supplied with this callback
});

watcher.registerDirectory(Paths.get("/var/www/"), 
true, // recursive
null,   // executor
(dirPath, relativeFilePath, kind) -> {
  // only kinds of ENTRY_CREATE will trigger this callback, not ENTRY_MODIFY, etc.
  // and this callback will be called for all creation events on all subdirectories
}, 
StandardWatchEventKinds.ENTRY_CREATE  // event kinds
);
```
