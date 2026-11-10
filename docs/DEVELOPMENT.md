# Development

Follow [BUILDING.md](BUILDING.md) to provision the supported toolchain. Keep the source checkout, terminal engine output, SDK, JDK, and private signing directory separate.

## Source conventions

Use the existing Android module and package boundaries. Keep UI state in presentation components and long-lived work in the established core owners. Prefer small changes that preserve cancellation, publication, and recovery contracts.

Use UTF-8 text and the formatting rules in `.editorconfig`. Keep explanatory comments to two lines per block. Comments should explain a non-obvious constraint rather than restate the code. Required upstream attribution remains intact.

Put maintained design and workflow documentation in `docs/`. Local investigation notes, screenshots, generated reports, machine-specific paths, build outputs, and credentials belong outside the tracked source.

## Validation

Run repository and dependency checks before opening a pull request. Run application tests appropriate to the affected behavior. For changes to startup, shutdown, editor/native integration, browser lifecycle, or the workbench, include device validation when available and state any testing limitation explicitly.

See [TESTING.md](TESTING.md) for test responsibilities and [DEPENDENCIES.md](DEPENDENCIES.md) for dependency changes.
