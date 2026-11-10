# Terminal input and output sources

The sources in this directory implement bounded PTY read/write progress. The adjacent patch records the change relative to the retained source baseline. Fixtures exercise ownership and continued output under cancellation and timing limits.

The active build is defined in `tools/terminal-engine-v2.json` at the repository root. Its hashes bind these sources to independent compiler outputs. Use the repository build and testing guides; generated artifacts and test reports belong outside this directory.
