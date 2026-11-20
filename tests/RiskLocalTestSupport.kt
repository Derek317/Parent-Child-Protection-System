package app.family.igguard

import android.content.Context

/** For tests that do not care about the page: consents to exactly the policy and method that are pending now. */
internal fun RiskLocal.accept(ctx: Context): Boolean = accept(ctx, policyId(ctx), policyMethod(ctx))
