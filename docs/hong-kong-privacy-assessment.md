# Preliminary Hong Kong privacy assessment

Prepared 6 November 2026, Hong Kong time, for the family-only architecture described in [privacy-data-flow.md](privacy-data-flow.md). This is a technical/legal issue assessment, not a finding that the app is lawful or unlawful and not a substitute for advice from a Hong Kong lawyer.

## Evidence and research limits

The code was inspected directly. Attempts to retrieve Hong Kong e-Legislation, PCPD guidance and the relay privacy documentation were blocked by this environment's network proxy (HTTP 403). The statutory framework below is preliminary; current wording, commencement/amendments, applicable cases and provider practices have not been verified live. No passage below is presented as a freshly verified quotation or professional legal opinion. Links at the end are references to verify, not evidence that their current contents were retrieved.

## Overall assessment

There is no code evidence of a developer-operated central family-message archive or developer receipt of plaintext. Families hold the pairing material and parent devices hold the decrypted archive. This materially differs from a provider collecting everyone's readable conversations on its own servers.

That architecture supports a narrower assessment of the developer's role. It does not alone determine legal status: the PDPO concept of a data user concerns actual control over collection, holding, processing or use, including joint/common control. Lack of plaintext access is important but not the entire test. Merely supplying software should not automatically be equated with being the data user for every family's records either. The actual operation, relationships and any separately collected support/metadata must be assessed.

No identified fact establishes that the app is inherently unlawful merely because it relays a message to a parent. Equally, Android permission, encryption and a family-use label are not blanket legal authorization for every installation or disclosure.

## Questions under the PDPO framework

| Question | Preliminary assessment | What remains to verify |
| --- | --- | --- |
| Can a parent use the domestic-purpose exemption? | Section 52 may apply to an individual's data held only for personal/family/household affairs. Private parental safeguarding is a plausible context. | Apply the exact statutory test to actual use, third-party correspondence and any later disclosure. The exemption should not automatically be extended to a developer, relay operator or institution. |
| Does every correspondent have to consent? | The PDPO is not a universal rule requiring both participants to approve every processing operation. Account labels/message contents can nevertheless be personal data when an individual is identifiable. | Consider fair/lawful collection, purpose, necessity and any applicable exemptions. Check other relevant laws separately; do not turn this PDPO observation into permission for secret interception or unauthorized access. |
| Does a parent-role selection authorize monitoring? | No identity or parental-responsibility verification is implemented. Explicit pairing helps confirm a connection, not the relationship's legal authority. | Determine whose authorization is appropriate for the child's age/circumstances and how it is communicated. Do not assume parents can waive every right of a child or correspondent. Government-ID collection is not prescribed by this assessment. |
| Is parent-controlled retention unlawful? | The local "until deleted" setting is not automatically unlawful. This assessment does not prescribe a universal 30/90-day expiry. | If the domestic exemption applies, do not simply impose an institutional retention rule on private family records. Where DPP2 applies, retention needs a purpose-based assessment. Provider-side/relay retention is a separate question. |
| Does encryption settle compliance? | It is a meaningful security safeguard. It does not resolve authority, fair collection, purpose limitation, rights or necessary retention. | Assess DPP4 security and any applicable processor safeguards, as well as the relay's actual processing/terms. Ciphertext and metadata are not automatically outside privacy law. |
| Does school use change the answer? | No school console or institutional monitoring feature was found. | A private-family-use description does not determine a school's legal status if it actually organizes or controls monitoring. No automatic developer liability or automatic institutional exemption is inferred. |

Where the PDPO applies without an exemption, the assessment should cover all six Data Protection Principles: collection purpose/manner, accuracy/retention, permitted use, security, openness and access/correction. Consent is not a substitute for the other requirements. A general child-protection purpose should not be treated as an automatic crime-prevention or other exemption for all records.

## Matters the implementation already supports

- Local parent archive; no implemented developer message store, dashboard or key-recovery service.
- Application-level encryption before relay upload; random family pairing secret and authenticated parent controls.
- Outgoing DMs excluded from alerts. This protection does not extend to the separate typed comment/story-reply path, which can forward unsent drafts and ordinary text; see the disclosure issue below.
- Explicit pairing confirmation and security-code comparison; existing Android special-access guidance.
- Parent-controlled deletion, isolated family scopes, disabled backup/transfer and generic lock-screen alert content.
- No added microphone, screenshot or media-library permission and no automatic content-analytics SDK found.

These are safeguards, not a legal certification or assurance against all unauthorized use.

## Specific issues to resolve before claiming compliance

1. **Complete, accessible notices and actual capture scope.** The README is not an in-app acknowledgment. Existing pairing text and conditional child monitoring notification are not a complete age-appropriate explanation of all captured/forwarded data, retention, relay involvement and authority. In particular, the existing `flushPendingCommentReplyRunnable` forwards distinct typed comment/story-reply text of at least two trimmed characters after debounce/flush, without requiring a keyword match or evidence of Instagram submission. It can disclose an unsent draft to the paired parent. An incoming-DM-only or keyword-alert-only description would be inaccurate. Determine whether that existing behavior fits the intended privacy boundary and explain it accurately, or obtain authorization for a separate behavior change. Determine the appropriate parent/child notice and acknowledgment process without claiming an Android grant is legal consent by itself.
2. **Authority and misuse handling.** Decide how to explain authorized parental/guardian use, obtain any appropriate declaration and handle unauthorized pairing reports. Existing pairing proves knowledge of an invitation, not parental responsibility. A disclaimer does not create that responsibility. Avoid collecting unnecessary identity documents as a default response.
3. **Publisher identity and contact.** Identify the actual publisher and a verified contact channel for privacy/abuse requests. No private support channel is invented in these documents. Do not invite users to publish messages, invitations or keys in GitHub issues.
4. **Relay facts and arrangements.** Verify the provider/operator, hosting jurisdictions, cache/log retention, deletion, subprocessors and service terms. Assess whether applicable processor arrangements and overseas-processing safeguards are adequate. Overseas processing is not declared automatically unlawful; the current legal requirements, including any relevant commencement provisions, need verification.
5. **Device and recipient risks.** Family invitations expose the shared secret; parent devices store readable archived text. Assess proportionate protections and accurate disclosures. A signed APK is not an assurance against device compromise, token sharing or misuse by a recipient.
6. **Other applicable rules.** Obtain targeted advice on unauthorized computer access/interception/confidentiality issues for the actual authorized device-side capture flow. Check current Instagram/Meta terms and Android/Google Play monitoring/accessibility requirements independently. A platform-policy problem and a breach of Hong Kong law are different determinations; no finding is made here about current terms or store eligibility.
7. **Actual ongoing practice.** Reassess if a backend, analytics, content-bearing support uploads, school administration, research/training use or materially broader media capture is introduced. A current no-backend architecture does not describe those future uses.

No changes to monitoring, retention or permissions are made by this assessment. The parent-controlled archive remains as implemented. The above items are an assessment of outstanding work, not a claim that each proposal is an expressly prescribed statutory requirement.

## Focused questions for a Hong Kong reviewer

- On the documented facts, who is the data user for message content, relay metadata and any independently submitted support data? Is anyone acting as a processor, and for whom?
- When does section 52 cover a parent's private records, and what happens when a particular record is disclosed outside that context for safeguarding or evidence?
- What notice/authority arrangements are appropriate for the intended 12–16-year-old users, their parents and third-party correspondents?
- Do the actual accessibility/notification collection methods raise another applicable statutory or confidentiality issue, despite authorized access to the child's device?
- Are the relay arrangements and current notices sufficient under the requirements that actually apply? What changes are necessary before making a compliance claim?

## Primary/provider references awaiting live verification

- [Personal Data (Privacy) Ordinance, Cap. 486](https://www.elegislation.gov.hk/hk/cap486): section 2 definitions, section 52 domestic purposes and Schedule 1 Data Protection Principles; check all relevant current provisions and exemptions.
- [PCPD: The Six Data Protection Principles](https://www.pcpd.org.hk/english/data_privacy_law/6_data_protection_principles/principles.html).
- [Privacy Commissioner for Personal Data](https://www.pcpd.org.hk/): obtain current children/privacy, notification and processor guidance as applicable.
- [ntfy documentation](https://docs.ntfy.sh/) and [provider privacy documentation endpoint](https://ntfy.sh/docs/privacy/): verify current availability, provider identity and the policy actually applying to the public `ntfy.sh` service. Self-hosted software defaults do not establish the public service's deployed configuration.
