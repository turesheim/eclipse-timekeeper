# Development guides

Use Java 21, Python 3.13, Node 24 and the checked-in Maven wrapper.
Start with [repository setup](REPOSITORY.md); run commands from the repository root.

| Task | Guide |
| --- | --- |
| Understand component boundaries | [Architecture](../architecture/README.md) and [API v1](../architecture/API-V1.md) |
| Make and verify a change | [Agent workflow and affected quality checks](AGENT-WORKFLOW.md) |
| Change the browser client | [Web implementation](WEB-CLIENT.md) and [browser acceptance](WEB-ACCEPTANCE.md) |
| Change the editor | [Journal integration](JOURNAL-EDITOR.md) and [Markdown component](MARKDOWN-EDITOR.md) |
| Change the Java HTTP client | [HTTP client](HTTP-CLIENT.md) |
| Verify installed Eclipse clients | [Remote base client](REMOTE-ECLIPSE.md) and [optional Mylyn integration](REMOTE-MYLYN.md) |
| Package Synology builds | [Candidate, promotion and publication](SYNOLOGY-PACKAGING.md) |
| Publish releases | [Release process](RELEASING.md) |
| Use current product names | [Dagsverk naming transition](BRANDING.md) |
| Find earlier decisions | [Change history](CHANGES.md), [dated upgrade plan](UPGRADE-PLAN.md), [verification reports](../verification/README.md) |

The web client uses native modules and Bootstrap 5.3.8. The server and Eclipse
reactors compile the same canonical Java sources from `common/`.
User workflows belong in [operations](../operations/README.md); dated test receipts
belong in verification, with their source identity and limits.
