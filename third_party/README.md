# third_party

`zxing-core-3.5.3.jar` is ZXing core 3.5.3 (Apache-2.0, pure Java), unmodified, copied from Maven Central. It is compiled in through `files('libs/zxing-core-3.5.3.jar')`; the build copies it to `app/libs/` and refuses to continue unless its SHA-256 equals the one in `zxing-core.sha256`.

- Downloaded from https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar and again from https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar (identical bytes).
- Size: 607650 bytes
- SHA-256: `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`
- SHA-1: `ca1349214a356cd7958651b2d5a0e1f3811a9c4b` (equals the `.sha1` Maven Central publishes)
- MD5: `b5269d7bf1eea1d064240c342829b117` (equals the published `.md5`)
- Maven Central publishes no `.sha256` for this file.
- PGP: `core-3.5.3.jar.asc` verifies as a good signature by key fingerprint CE32 85F3 2068 5193 D11F EA01 F6CE 9695 C931 8406 ("Sean Owen (ZXing)"), signed 2024-01-31. This shows the signature matches that key; that the key belongs to the ZXing maintainer was not established (the key was fetched from keyserver.ubuntu.com and is not in any trust chain).

Rule: replace the jar only together with `zxing-core.sha256`, after the same checks (second download, published digests, signature).
