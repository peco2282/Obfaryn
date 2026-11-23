# Community and additional-protection development policy

[日本語](edition-policy.ja.md)

The public feature set remains the community baseline. Existing features continue to receive fixes, compatibility work and ordinary improvements. This policy does not announce a paid release, change any license, or establish a protection guarantee.

## Placement of changes

| Area | Destination |
| --- | --- |
| Public API, extension contracts, CLI, Gradle integration and existing platform support | Community |
| Existing renaming, literal protection, call indirection, control flow and explicitly selected virtualization | Community, including their fixes and ordinary improvements |
| Verification, structural budgets, rollback, basic reports and distribution auditing | Community |
| New predicate indirection and Code Capsule implementations | Additional-protection candidates, pending evaluation |
| Recovery studies, application-specific Paper probes and rejected prototypes | Not maintained in the active product branches |

A new implementation is a paid candidate only when its additional value, compatibility constraints and runtime costs can be explained and tested. A recent commit or a more complex encoding is not by itself a reason to charge. Safety and correctness fixes must reach community even when first discovered while developing a candidate.

## Branch workflow

- `main` and `codex/community` contain the community changes prepared from the public baseline.
- `codex/additional-protection` builds on community and contains the additional-protection candidates. Merge community fixes into it; do not merge this branch wholesale into `main`.
- `codex/pre-commercial-split` preserves the original unsplit history for reference. It is an archive, not an integration branch.

The candidate branch is a local staging branch. Review their contents and licensing before deciding where to publish them. A branch boundary is not a separately packaged or separately licensed product. The next packaging step, if justified by evaluation, is an extension using the public assembly contracts; it is not implemented by this history split.

Existing MPL-covered code and the output/runtime exception retain their terms. Moving code to another branch or file does not itself change its license. Evaluation records describe their tested scope and must not be presented as general resistance or performance guarantees.
