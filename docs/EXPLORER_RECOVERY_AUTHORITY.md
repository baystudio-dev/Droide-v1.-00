# Reserved copy-recovery authority

A fresh ProjectCopyJournal-created `.droide/copy-recovery` directory was reproducibly classified as misplaced user content and moved through FileRepository.moveObserved. This displaced authority, not an individually authorized retained payload. The two regression assertions failed against the accepted657 production JAR before this repair.

ProjectCopyJournal now owns one exact namespace predicate for its local recovery and archive paths. Ordinary write, create-directory, delete and both move operands refuse these roots and descendants, checking literal and existing canonical path normalization. FileRepository invokes this alongside its existing directory-custody checks at the same admission and callback boundaries. ExplorerRecoveryPolicy shares the predicate within its existing depth-two recovery listing rule. No visual UI code changes.

The journal's explicit inspection, recovery and retirement APIs remain separate and unchanged. The guard does not instantiate the journal, scan it, or write metadata. Sync behavior and creation of `.droide` itself remain as before. Moving/deleting the ancestor `.droide` was already refused by directory custody. Legitimate `.droide/MyProject` and near-prefix names remain recoverable. Local-delete custody already had a downstream guard; this increment makes no claim that it was movable.

VS Code explorerModel.ts at e13716ce975fc4597cb8c2228471ac4783bf4529 is the primary model reference: Explorer presentation is derived from workspace/resource authority rather than granting mutation authority by display alone. workingCopyFileService's participant and failure lifecycle is an additional reference. These references do not define Droide's reserved journal namespaces, which are established by ProjectCopyJournal itself. No upstream implementation is copied.

All tests use fresh synthetic roots. This protects ordinary FileRepository paths, not arbitrary native processes or external filesystem writers. There is no payload deletion, new release permission, schema/default activation, device validation or global closure claim.
