# Architecture

## Workbench

`app/src/main/java/com/baystudio/droide/ui/` contains the Compose workbench, editor integration, Explorer, terminal presentation, browser hosts, agent chat, and settings. Presentation observes application-owned state; it does not own long-running process lifetimes.

`MainActivity` hosts the workbench. `DroideApplication` coordinates application ownership, admission of new work, startup, and explicit shutdown. `WorkspaceRuntimeHost` manages resources whose lifetime spans individual composable instances.

## Core services

`app/src/main/java/com/baystudio/droide/core/` contains project operations, persistence, agent execution, provider adapters, language services, extension installation, toolchain management, and runtime ownership.

| Concern | Ownership rule |
| --- | --- |
| Editor documents | Preserve live buffers and recovery state across view recreation |
| File publication | Validate source identity and publish changes through owned filesystem operations |
| Recovery | Keep recoverable data until a durable replacement or explicit user action establishes the next state |
| Agent execution | Keep provider protocol state, tool execution, and public conversation content distinct |
| Native processes | Track and close owned processes without acting on unrelated processes |
| Browser views | Release the exact owned view and resources when its session ends |
| Exit | Stop admission, preserve recoverable work, stop owned work, release resources, and close the application |

Cancellation, timeouts, and retries must preserve these boundaries. A retry must not replay an already completed provider operation or publish an unverified file replacement.

## Runtime and native code

The application uses a persistent Ubuntu ARM64 workstation through PRoot. Toolchains and external packages are installed on demand. Native filesystem operations are implemented in `app/src/main/cpp/`. Terminal components and their corresponding source are maintained under `third_party/termux/`.

Native Java declarations, C registration tables, packaged libraries, and APK contents have explicit verification. A host test validates its stated host behavior; Android lifecycle and device behavior require Android testing.

## Configuration and integrations

Workspace configuration, extension metadata, provider settings, MCP connections, and reusable skills are handled by core services. Built-in and external agents share application capabilities through controlled adapters. Provider and transport adapters retain their protocol-specific behavior while presenting common session state to the workbench.

Maintained package catalogs are bundled under `app/src/main/assets/`. `backend/` contains the optional account authentication service. Authentication configuration and signing credentials are deployment inputs, not repository content.

## Legal assets

Original third-party notices and license texts are packaged in `app/src/main/assets/legal/`. Their catalog is generated from maintained metadata and checked against the packaged files. Required attribution is part of the distributable source and application.
