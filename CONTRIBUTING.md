# Contributing

Keep changes focused and describe the user-visible problem they solve. Read the architecture and development guides before changing shared application services.

## Issues

For a bug report, include the application version, Android version, device model, steps to reproduce, expected behavior, and observed behavior. Attach a small example project when it is needed to reproduce the problem. Remove credentials, private source, and personal information from attachments.

Use the private reporting process in [SECURITY.md](SECURITY.md) for vulnerabilities.

## Pull requests

1. Start from the current default branch and keep unrelated changes separate.
2. Add or update behavioral tests for meaningful changes. Repository and dependency policies have their own tests.
3. Run the checks described in [docs/TESTING.md](docs/TESTING.md).
4. Update the relevant maintained guide when behavior or a public workflow changes.
5. Describe the problem, resulting behavior, validation performed, and remaining limitations.

Tests must exercise behavior, observable contracts, or repository policy. Avoid tests that merely search for a particular comment, formatting choice, or obsolete implementation detail.

Keep implementation comments concise: at most two lines per comment block. Explain an invariant, ownership rule, or non-obvious constraint. Put longer explanations in documentation. Preserve required third-party copyright and license notices.

Maintain the existing application design and interactions unless the change explicitly concerns them. Use existing lifecycle, work-admission, publication, and recovery abstractions rather than creating parallel state owners.

By submitting a contribution, you agree that your contribution is licensed under the repository's applicable license. Preserve existing attribution and third-party license boundaries.
