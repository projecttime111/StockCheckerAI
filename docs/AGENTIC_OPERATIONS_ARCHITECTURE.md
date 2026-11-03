# StockPilot Agentic Operations Architecture

Assessment date: 7 October 2026.

## Recommendation

Adopt the workflow pattern behind Oracle's Ledger Agentic App, not its entire enterprise accounting scope. StockPilot's strongest initial position is an evidence-backed inventory and cash exception workspace for Tally-using retailers and distributors. Keep their ERP as the accounting source of record. Expand to other ERPs only through tested adapters and equivalent reconciliation checks.

StockPilot is moving in a similar direction, but is not currently equivalent to an autonomous accounting application. The existing import, review, calculation and audit infrastructure is useful groundwork. General goal execution, durable scheduled investigations, governed agent tools and verified ERP write-back are not implemented.

Suggested positioning after pilot validation: "StockPilot helps business owners find stock and cash issues in their ERP data, understand the evidence, and complete the next step with approval."

## Oracle Lessons

Oracle's public 26C documentation describes an accounting workspace with conversational inquiries, explanatory insights, monitoring, period-close visibility and background spreadsheet results. It explicitly ties responses to the user's authorized Fusion data. These are experience and governance patterns worth learning from, not proof of StockPilot feature parity. [Oracle 26C documentation](https://docs.oracle.com/en/cloud/saas/readiness/erp/26c/fins26c/26C-fin-wn-f50687.htm).

The public 26D documentation goes further: objectives become multi-step work, users can review a plan, SOPs guide execution, and completed work includes evidence. It also describes specialized analysis and policy-controlled exception resolution. Its documented automatic correction scope is specific, including missing currency rates and inactive/end-dated segment values in journal processing; it is not unrestricted autonomous accounting. [Oracle 26D documentation](https://docs.oracle.com/en/cloud/saas/readiness/erp/26d/fins26d/26D-fin-wn-f51915.htm).

The supplied SharePoint articles were inaccessible. The Customer Connect resource list is public, but full resources require login. Therefore the pasted 27A retirement timeline and detailed production subscription conditions are attributed to the user's supplied note, not independently confirmed here. Public release documentation establishes capabilities, not the precise commercial entitlement of an individual customer. [Customer Connect resources](https://community.oracle.com/customerconnect/discussion/970926/26c-ledger-agentic-app-resources-faqs-prompting-tips-whats-new).

An independently developed StockPilot workflow need not be built on Oracle's platform. Do not copy internal Oracle assets or describe the product as Oracle-integrated without an actual supported integration. The proposed design below uses the existing Spring Boot, PostgreSQL and Next.js application.

## Current Product Position

Code present is not the same as deployed, business-validated or safe for unsupervised use. The 5 October audit batch passed 273 backend tests with one real-data replay skipped, 44 web tests and a web production build. Those are previous verification results, not a new test run or production certification today. [Audit evidence](AUDIT_FIXES_2026-10-05.md).

| Capability | Current state | Business implication |
| --- | --- | --- |
| Mixed Tally imports | Classification, staging, voucher routing and review code exists | A useful foundation for turning real exports into structured records |
| Reviewed import execution | Dry run, confirmation, snapshot dates, effect tracking and duplicate safeguards exist | Retain these controls; interrupted-commit recovery is still a blocker |
| Inventory and transactions | Products, stock movements, sales, purchases, parties and balances exist | Operational data foundation, not a complete general ledger or financial close system |
| Cash and UPI retail totals | Confirmed amount-only retail records with source fingerprints exist | Can record counter collections without pretending to know the products sold |
| Returns and transfers | Financial-note and guarded physical-return/transfer paths exist | Not a general manufacturing or production-planning engine |
| Reporting and customer balances | Shared snapshot-aware balance service, exports and portal exist | Balance consistency was improved; still reconcile the client's source totals |
| Forecasts and dead stock | Services and screens exist | Invoice-only history is still missed in key demand paths, so recommendations are not yet trustworthy for that import mode |
| Profit explanations | Rules and comparative calculations exist | Not proven root-cause analysis; cost-unit consistency and comparable date windows need attention |
| AI assistant | Keyword branches select service calculations and save evidence | It is a constrained rule-based assistant, not an LLM planning/execution runtime |
| LLM integration | Provider interface and configuration placeholder exist | `LlmAiProvider.complete` makes no outbound model request, even when configured |
| Action buttons and reminders | Reminder records exist; dead-stock actions can be recorded in audit | Recording a suggested action is not executing a discount, transfer or supplier return |
| OCR and live sync | Fabricated OCR disabled; no verified live ERP collector in this review | Do not promise automatic bill capture or continuous synchronization today |

Key code evidence:

- [Assistant keyword routing](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/AiAssistantService.java:73>).
- [LLM provider placeholder](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/LlmAiProvider.java:24>).
- [Movement-based forecast input](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/ForecastService.java:42>).
- [Dead-stock action recording only](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/DeadStockActionService.java:73>).
- [Confirmed amount-only retail records](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/RetailCounterSaleService.java:69>).
- [Source date confirmation](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/SmartImportSnapshotDateService.java:27>).

Additional code-inspection caution: `ProfitInsightService.bestMarginProducts` multiplies item quantity by `costRate`, whereas the new sales path treats costRate as a base-unit cost. Converted-unit margin reporting needs a regression test and consistent base quantities before an agent narrates those margins. `currentMonth()` also compares an incomplete current month with a complete previous month. A business-facing comparison must label coverage and offer equivalent periods. These are inspection findings, not newly reproduced/fixed defects in this architecture task. [Profit insight calculations](<C:/Users/Dhruv Chandak/Desktop/smartledger/ai_predication_/stockpilot-ai/backend/src/main/java/com/stockpilot/ai/service/ProfitInsightService.java:45>).

## First Useful Agent Workflow

Build an **Inventory and Cash Exception Desk**, initially triggered after a completed import or explicitly by the owner. Do not start with automatic journal entries or autonomous purchasing.

Example objective: "After the latest reviewed import, identify unresolved stock and receipt exceptions, explain the available evidence, and assign the next action for owner review."

| Check | Required evidence | Useful output | Boundary |
| --- | --- | --- | --- |
| Data readiness | Covered dates, source company, committed import, source totals and excluded rows | Missing/stale data warning and a request for a specific report | An August snapshot does not establish October's current stock |
| Negative stock | Product, warehouse, dated movements and closing snapshot | Relevant entries, possible causes and a count/reconciliation task | Do not change negative stock to zero or infer theft |
| Unmatched cash/bank row | Source voucher, direction, amount, date and any party/bill references | Candidate classifications with matching evidence | Cash/UPI receipt is not automatically a new sale; may be a transfer, loan or invoice collection |
| Customer collection | Reconciled outstanding and actual due-date evidence | Draft prioritized follow-up and supporting documents | Ledger-only closing balances cannot establish invoice-level overdue days |
| Supplier price change | Comparable item, base unit, discounts, tax treatment and period | Explainable price movement and a purchase-review task | Missing costs and incompatible units block a definitive claim |

For the shop's unitemized counter sales, preserve exactly the known facts: date, amount, collection method and source. If Cash and UPI were combined, their separate amounts are unknown. Missing product detail means missing item demand and cost, not zero demand or zero cost. A cycle count, itemized POS capture or additional source record is needed to recover that information.

Each exception should show owner, status, due date, financial/quantity impact where supported, evidence links, data-as-of date, calculation/rule version, known facts, possible explanations and the next safe action. Repeated scans should update the same issue rather than produce duplicate alerts. New source evidence should reopen or invalidate stale conclusions.

## Architecture

Keep a modular Spring Boot application. A collection of specialist "skills" can be ordinary typed service methods; there is no need to introduce a microservice or a separate model for each one.

```text
ERP export / later read-only local connector
  -> Existing classify, stage, review, dry-run and commit pipeline
  -> Canonical records + dated source references + coverage checks
  -> Deterministic exception checks and numeric analysis
  -> Evidence-backed explanation / optional bounded LLM planner
  -> Proposed action + server-side policy and approval
  -> Existing transactional business service
  -> Verified result + audit trail + exception status
```

Recommended small modules, added only as their phase needs them:

| Module | Responsibility |
| --- | --- |
| Data readiness service | Declare coverage, as-of date, missing dimensions, exclusions and reconciliation status per capability |
| Exception service | Tenant-scoped issue identity, evidence, assignment, transitions, severity, recurrence and resolution |
| Analysis service | Deterministic comparisons using the existing balance/stock services and corrected common demand/cost inputs |
| Agent tool adapter | Allowlisted typed read tools such as balance lookup, source retrieval and stock reconciliation; no arbitrary SQL |
| Action approval service | Persist exact proposed inputs, policy version, approver, expiry and input-data version |
| Durable worker | Persist steps, ownership/lease, bounded retries and outcome; recover without repeating effects |

Keep financial arithmetic and eligibility rules in Java/SQL using decimal types. Use a model to interpret requests and explain permitted evidence, not to invent amounts, select tenant identity or override validations. One bounded language model is enough to start. Model count does not establish product quality.

Read tools must enforce tenant and field permissions before retrieving evidence. Background jobs need explicit tenant context and must recheck the initiating user's permissions. If an external model is used, require appropriate customer authorization, minimize sensitive data and define provider retention behavior. ERP descriptions, uploaded documents and SOP text are untrusted input; they cannot grant permissions or redefine approved tools. Convert authorized policy choices into validated, versioned settings rather than executing arbitrary document instructions.

Persist only the workflow objects needed: `OperationalException`, `AnalysisRun`, `ActionProposal`, `ApprovalDecision`, and `ExecutionAttempt`. Link them to existing source files, rows and business records. Do not create a second inventory ledger or a second editable copy of import review decisions.

An approval must bind to the exact action, arguments and data version. Revalidate immediately before execution. Changed source data, revoked permissions or an expired approval require fresh review. Use an idempotency key and stored result for every effect; a network timeout means check the outcome, not blindly repeat a payment or adjustment. Verify the expected postcondition and retain actor, evidence and before/after values.

## Autonomy Boundaries

| Level | Allowed behavior | Initial position |
| --- | --- | --- |
| Observe | Read authorized data, calculate exceptions and explain sources | Start here after calculation/permission fixes |
| Propose | Prepare tasks, draft reminders, propose corrections and impact previews | Human decides; no financial posting |
| Execute after approval | Run an exact approved command through existing services | Enable per workflow after retry and permission tests |
| Limited unattended work | Refresh analyses or create internal tasks under explicit policies | Later, with monitoring, deduplication, cancellation and escalation |
| Autonomous financial posting | Send payments, alter ERP accounts, adjust stock or create accruals without case approval | Out of initial scope |

Read-only ERP connectivity is a separate delivery project from ERP write-back. A reviewed change inside StockPilot must not be presented as a posted change in Tally. Show source-of-record status and reconciliation explicitly.

## Delivery Order

| Phase | Work | Exit condition |
| --- | --- | --- |
| 0. Reliable foundation | Close F1, F4, F7 and F8; finish F3 cost consistency; operational launch gates | Correct imported demand, role-safe evidence, proper invoice identity, tested crash recovery and separate backup restore |
| 1. Readiness and exceptions | Post-import readiness check and exception inbox using existing review state | Missing/stale data produces "insufficient data"; reruns do not duplicate issues; evidence links work |
| 2. Explain and assign | Plain-language explanations, owners, due dates and source-linked reports | Owner can verify every amount; unsupported questions abstain; restricted data stays restricted |
| 3. Fresh data and monitoring | Read-only local ERP collector, import checkpoints, scheduled evaluations and deduplicated notifications | Visible last sync and data coverage; offline/retry behavior tested; no duplicate imports |
| 4. Approved actions | Exact approval, durable execution, status recovery and verified outcomes | Double-click/retry/worker crash cannot duplicate effects; old approvals cannot run on changed data |
| 5. Additional domains | Select one validated retailer or manufacturer workflow | Measurable customer benefit and required source data exist before broader automation |

The next coding task should be **F4 permission enforcement and F1 shared demand inputs**, not a general autonomous agent. Then build the readiness/exception workflow. Existing auth, posting and source-import services should remain the enforcement points. A deterministic first release is acceptable; LLM explanations can follow after the evidence contract is reliable.

## Business Value and Differentiation

Oracle's documented workspace is designed around Fusion accounting. StockPilot's proposed audience is an owner operating an existing small-business ERP who needs an understandable, evidence-linked daily action list across imports, stock and collections. That is a narrower product and a plausible market hypothesis, not a claim that Oracle or other ERPs lack these capabilities.

Customers will not buy another dashboard merely because it says "AI". A customer with satisfactory native reports and workflows may not need StockPilot. The reason to choose it must be demonstrated: fewer manual reconciliations, less report switching, faster resolution, easier onboarding and accurate cross-source visibility. Validate willingness to pay before expanding to more ERPs or buying multiple model subscriptions.

For retailers, start with stock discrepancies, reconciliation of itemized versus amount-only sales, and collection follow-ups. Add POS/item capture where missing sales details constrain analysis. For manufacturers, first assess available BOM versions, production orders, material issues/receipts, yield, scrap and batch data. Without those, raw-material shortages, yield variance and production costing cannot be inferred reliably from closing stock alone. Full MRP, machine scheduling and autonomous manufacturing adjustments are not near-term extensions of the current ledger.

Run a supervised pilot with two or three comparable businesses and a pre-agreed source period. Record their baseline effort, then measure:

- Time from receiving source data to reconciled, usable records.
- Time spent investigating an exception and whether the cited evidence supports its resolution.
- Alert precision: useful issues versus false or duplicate alerts, with owner adjudication.
- Agreement of stock, invoice and outstanding totals with agreed source reports.
- Approved actions completed, failed, retried and duplicated; any duplicate financial effect is a stop condition.
- Whether missing data and unsupported claims are communicated correctly.

Revenue growth, cash recovered and inventory reduction require observed results over time; they must not be inferred merely from generating recommendations. Only expand automation after these checks pass and a business owner confirms the workflow actually saves effort.

## Implementation Scope

This architecture review added this document only. It did not add agent execution, change application code, repair business data, apply migrations, rerun the test suite, restart servers or deploy. The current application remains subject to the [open launch findings](LAUNCH_READINESS_REVIEW.md).
