# Image and voice detection feasibility

This is an assessment, not a shipped image/audio feature.

| Capability | Initial approach | Difficulty and data needs |
| --- | --- | --- |
| Explicit-image signals | A licensed pretrained on-device classifier; OCR for text inside images | Medium for explicitly selected files. No foundation-model training needed, but a representative evaluation set is needed for accuracy claims. A nudity score does not establish age, intent or grooming. |
| Voice-message text | A pretrained speech-to-text model followed by text-risk checks | Medium for accessible files; higher for dependable Cantonese/code-switching on modest phones. Validate slang, accent, noise, latency, battery and model size. Android's on-device speech facilities/language support vary; do not assume cloud-free operation without checking. |
| Automatic Instagram media access | Requires access to actual media or system-approved capture | High. Notification/accessibility text hooks do not provide the underlying image/audio files. Instagram private storage is isolated; supported capture may be unavailable. Broad screenshot/audio capture can include outgoing private conversations and bystanders. |
| Grooming assessment | Context over a conversation and human review | High. Image classification or transcription alone does not identify a grooming process. Fine-tuning and reliable safety claims need carefully labelled, representative evaluation and appropriate specialist input. |

A future minimal option is explicit user-selected/shared media with temporary URI access and local processing where feasible. It should disclose any network processing, preserve the current outgoing-DM privacy boundary and avoid scanning whole libraries or recording audio in the background. Use appropriately consented/licensed evaluation material; do not collect children's private conversations as an automatic training dataset.

Training a large new model from scratch is unnecessary for a first prototype. Integrating pretrained models, acquiring media legitimately, validating local language performance and keeping privacy boundaries are the main work.
