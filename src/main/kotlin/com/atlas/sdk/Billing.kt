package com.atlas.sdk

import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Billing over the Frontend API (`/v1/client/billing/...`): the instance's plans,
 * the signed-in subject's subscription, invoices and card, and the hosted
 * Checkout / Billing Portal URLs the provider issues. There is no in-app card
 * form: card entry stays on the provider's hosted page. Pass `orgId` to act on an
 * organization the user administers instead of on the user.
 */

/** A number that the server may send as a JSON number or a numeric string. */
private fun JsonValue.flex(key: String): Double? =
    number(key) ?: string(key)?.toDoubleOrNull()

/** A display-only promotion on a plan. */
data class BillingPromotion(
    val label: String,
    val percentOff: Double?,
    val amountOff: Double?,
    val endsAt: Double?,
    val code: String?,
) {
    companion object {
        fun from(json: JsonValue) = BillingPromotion(
            label = json.string("label") ?: "",
            percentOff = json.flex("percent_off"),
            amountOff = json.flex("amount_off"),
            endsAt = json.flex("ends_at"),
            code = json.string("code"),
        )
    }
}

/** How a plan is priced for display ([BillingPlan.priceView]). */
data class BillingPriceView(
    val price: String,
    val originalPrice: String?,
    /** `month` or `year`. */
    val interval: String,
    val free: Boolean,
    val trialDays: Int?,
    val promotionLabel: String?,
    val promotionCode: String?,
    /** Minor units after any promotion. */
    val amount: Long,
)

/** A plan the instance sells. Amounts are minor units (cents). */
data class BillingPlan(
    val id: String,
    val name: String,
    val slug: String,
    /** `month` or `year`. */
    val interval: String,
    val amount: Double,
    val currency: String,
    val features: List<String>,
    /** `user` or `org`. */
    val audience: String,
    val provider: String?,
    /** `base` (the subject's main plan) or `addon`. */
    val kind: String?,
    val trialDays: Int?,
    val annualAvailable: Boolean,
    val annualAmount: Double?,
    val promotion: BillingPromotion?,
    val description: String?,
    val badge: String?,
    val sortOrder: Int?,
) {
    /**
     * The price, trial and promotion to show at [wantedInterval] (default the plan's
     * own). A yearly interval uses the annual amount when the plan offers one; an
     * expired promotion is ignored. Mirrors the web `planPriceView`.
     */
    fun priceView(
        wantedInterval: String? = null,
        locale: Locale = Locale.getDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): BillingPriceView {
        val own = if (interval == "year") "year" else "month"
        val chosen = wantedInterval ?: own
        val annual = if (annualAvailable) annualAmount else null
        val base = when {
            chosen == own -> amount
            chosen == "year" && annual != null -> annual
            else -> amount
        }
        val shown = if (chosen == own || (chosen == "year" && annual != null)) chosen else own
        val promo = promotion?.takeUnless { it.endsAt != null && it.endsAt <= nowMillis }
        var final = base
        if (promo?.percentOff != null && promo.percentOff != 0.0) {
            final = (base * (1 - promo.percentOff / 100)).roundToLong().toDouble()
        } else if (promo?.amountOff != null && promo.amountOff != 0.0) {
            final = max(0.0, base - promo.amountOff)
        }
        val discounted = promo != null && final < base
        return BillingPriceView(
            price = formatMoney(final, currency, locale),
            originalPrice = if (discounted) formatMoney(base, currency, locale) else null,
            interval = shown,
            free = final == 0.0 && base == 0.0,
            trialDays = trialDays?.takeIf { it > 0 },
            promotionLabel = promo?.label,
            promotionCode = promo?.code,
            amount = final.roundToLong(),
        )
    }

    companion object {
        fun from(json: JsonValue) = BillingPlan(
            id = json.string("id") ?: throw AtlasException(AtlasError.Decoding("Plan has no id.")),
            name = json.string("name") ?: "",
            slug = json.string("slug") ?: "",
            interval = json.string("interval") ?: "month",
            amount = json.flex("amount") ?: 0.0,
            currency = json.string("currency") ?: "usd",
            features = json.arr("features")?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList(),
            audience = json.string("audience") ?: "user",
            provider = json.string("provider"),
            kind = json.string("kind"),
            trialDays = json.number("trial_days")?.toInt(),
            annualAvailable = json.bool("annual_available") ?: false,
            annualAmount = json.flex("annual_amount"),
            promotion = json.obj("promotion")?.let { BillingPromotion.from(it) },
            description = json.string("description"),
            badge = json.string("badge"),
            sortOrder = json.number("sort_order")?.toInt(),
        )

        /** A minor-unit amount as a locale-formatted currency string (`12.34 USD` for an unknown code). */
        fun formatMoney(minor: Double, currency: String, locale: Locale = Locale.getDefault()): String =
            try {
                NumberFormat.getCurrencyInstance(locale).apply { this.currency = Currency.getInstance(currency.uppercase()) }.format(minor / 100)
            } catch (_: IllegalArgumentException) {
                "%.2f %s".format(Locale.ROOT, minor / 100, currency.uppercase())
            }
    }
}

/** A subject's subscription. [status] is null when the subject has never subscribed. */
data class BillingSubscription(
    val id: String?,
    val subjectType: String,
    val status: String?,
    val plan: BillingPlan?,
    val currentPeriodEnd: Double?,
    val cancelAtPeriodEnd: Boolean,
    val interval: String?,
    val scheduledPlanId: String?,
    val trialEnd: Double?,
    val trialEligible: Boolean,
    /** Every subscription the subject holds (base plan and add-ons). */
    val subscriptions: List<BillingSubscription>,
) {
    val isLive: Boolean get() = status in LIVE_STATUSES

    /** Every subscription of the read: [subscriptions], or the one it reports. */
    val all: List<BillingSubscription>
        get() = subscriptions.ifEmpty { if (status != null) listOf(this) else emptyList() }

    /** The live add-on subscriptions. */
    val liveAddons: List<BillingSubscription>
        get() = all.filter { it.plan?.kind == "addon" && it.isLive }

    companion object {
        /** Statuses in which a subscription is still running. */
        val LIVE_STATUSES = listOf("active", "trialing", "past_due")

        fun from(json: JsonValue): BillingSubscription = BillingSubscription(
            id = json.string("id"),
            subjectType = json.string("subject_type") ?: "user",
            status = json.string("status"),
            plan = json.obj("plan")?.let { BillingPlan.from(it) },
            currentPeriodEnd = json.flex("current_period_end"),
            cancelAtPeriodEnd = json.bool("cancel_at_period_end") ?: false,
            interval = json.string("interval"),
            scheduledPlanId = json.string("scheduled_plan_id"),
            trialEnd = json.flex("trial_end"),
            trialEligible = json.bool("trial_eligible") ?: false,
            subscriptions = json.arr("subscriptions")?.items?.map { from(it) } ?: emptyList(),
        )
    }
}

/** A stored invoice (billing history). */
data class BillingInvoice(
    val id: String,
    val number: String?,
    val status: String,
    val amountDue: Double,
    val amountPaid: Double,
    val currency: String,
    val hostedInvoiceUrl: String?,
    val invoicePdf: String?,
    val createdAt: Double,
) {
    companion object {
        fun from(json: JsonValue) = BillingInvoice(
            id = json.string("id") ?: throw AtlasException(AtlasError.Decoding("Invoice has no id.")),
            number = json.string("number"),
            status = json.string("status") ?: "",
            amountDue = json.flex("amount_due") ?: 0.0,
            amountPaid = json.flex("amount_paid") ?: 0.0,
            currency = json.string("currency") ?: "usd",
            hostedInvoiceUrl = json.string("hosted_invoice_url"),
            invoicePdf = json.string("invoice_pdf"),
            createdAt = json.flex("created_at") ?: 0.0,
        )
    }
}

/** The subject's default card. */
data class BillingPaymentMethod(val brand: String, val last4: String, val expMonth: Int, val expYear: Int) {
    companion object {
        fun from(json: JsonValue) = BillingPaymentMethod(
            brand = json.string("brand") ?: "",
            last4 = json.string("last4") ?: "",
            expMonth = json.number("exp_month")?.toInt() ?: 0,
            expYear = json.number("exp_year")?.toInt() ?: 0,
        )
    }
}

/** A live feature check. For UI only: enforce on your server or from the token. */
data class BillingFeatureCheck(
    val feature: String,
    val allowed: Boolean,
    val source: String?,
    val remaining: Double?,
    val granted: Double?,
    val usage: Double?,
    val unlimited: Boolean,
)

/** The result of checking a discount code. */
data class BillingCouponCheck(
    val valid: Boolean,
    /** Why it is not valid (`invalid`, ...). */
    val reason: String?,
    val percentOff: Double?,
    val amountOff: Double?,
    val currency: String?,
    val duration: String?,
    val durationMonths: Int?,
)

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun billingPath(path: String, vararg query: Pair<String, String?>): String {
    val q = query.filter { it.second != null }.joinToString("&") { "${it.first}=${enc(it.second!!)}" }
    return "/v1/client/billing/$path" + if (q.isEmpty()) "" else "?$q"
}

private fun subscriptionPath(action: String, subscriptionId: String?): String =
    if (subscriptionId == null) "/v1/client/billing/subscription/$action"
    else "/v1/client/billing/subscriptions/${enc(subscriptionId)}/$action"

private fun JsonValue.hostedUrl(): String =
    string("url")?.takeIf { it.isNotEmpty() } ?: throw AtlasException(AtlasError.Decoding("The server returned no checkout URL."))

/** The plans the instance sells, in display order. */
suspend fun AtlasClient.billingPlans(): List<BillingPlan> =
    authedRequest("GET", "/v1/client/billing/plans", null).arr("data")?.items.orEmpty().map { BillingPlan.from(it) }.sortedBy { it.sortOrder ?: 0 }

/** The subject's subscription (and add-ons); [orgId] reads an organization's. */
suspend fun AtlasClient.billingSubscription(orgId: String? = null): BillingSubscription =
    BillingSubscription.from(authedRequest("GET", billingPath("subscription", "org_id" to orgId), null))

/** The subject's invoices, newest first. */
suspend fun AtlasClient.billingInvoices(orgId: String? = null): List<BillingInvoice> =
    authedRequest("GET", billingPath("invoices", "org_id" to orgId), null).arr("data")?.items.orEmpty().map { BillingInvoice.from(it) }

/** The subject's default card, or null when there is none. */
suspend fun AtlasClient.billingPaymentMethod(orgId: String? = null): BillingPaymentMethod? =
    authedRequest("GET", billingPath("payment_method", "org_id" to orgId), null).obj("payment_method")?.let { BillingPaymentMethod.from(it) }

/**
 * Start a provider Checkout and return its hosted URL; open it in a Custom Tab.
 * [successUrl] / [cancelUrl] are where the provider returns, usually your app link.
 */
suspend fun AtlasClient.billingCheckout(
    planId: String,
    successUrl: String,
    cancelUrl: String,
    orgId: String? = null,
    interval: String? = null,
    couponCode: String? = null,
): String = authedRequest(
    "POST",
    "/v1/client/billing/checkout",
    jsonBody(
        "plan_id" to jstr(planId), "success_url" to jstr(successUrl), "cancel_url" to jstr(cancelUrl),
        "org_id" to jstr(orgId), "interval" to jstr(interval), "coupon_code" to jstr(couponCode?.takeIf { it.isNotEmpty() }),
    ),
).hostedUrl()

/** Open the provider's Billing Portal (card, invoices, cancel) and return its hosted URL. */
suspend fun AtlasClient.billingPortal(returnUrl: String, orgId: String? = null, subscriptionId: String? = null): String =
    authedRequest(
        "POST",
        "/v1/client/billing/portal",
        jsonBody("return_url" to jstr(returnUrl), "org_id" to jstr(orgId), "subscription_id" to jstr(subscriptionId)),
    ).hostedUrl()

/** Switch a live subscription to another plan or interval, with no checkout. */
suspend fun AtlasClient.billingChangePlan(
    planId: String,
    interval: String? = null,
    atPeriodEnd: Boolean = false,
    orgId: String? = null,
    subscriptionId: String? = null,
): BillingSubscription = BillingSubscription.from(
    authedRequest(
        "POST",
        subscriptionPath("change", subscriptionId),
        jsonBody("plan_id" to jstr(planId), "schedule_at_period_end" to jbool(atPeriodEnd), "org_id" to jstr(orgId), "interval" to jstr(interval)),
    ),
)

/** Cancel at the end of the period, or now with [immediately]. */
suspend fun AtlasClient.billingCancel(immediately: Boolean = false, orgId: String? = null, subscriptionId: String? = null): BillingSubscription =
    BillingSubscription.from(
        authedRequest("POST", subscriptionPath("cancel", subscriptionId), jsonBody("immediately" to jbool(immediately), "org_id" to jstr(orgId))),
    )

/** Undo a cancellation scheduled for the end of the period. */
suspend fun AtlasClient.billingRestore(orgId: String? = null, subscriptionId: String? = null): BillingSubscription =
    BillingSubscription.from(authedRequest("POST", subscriptionPath("restore", subscriptionId), jsonBody("org_id" to jstr(orgId))))

/** May the subject use [feature] (needing [required] units)? For UI only. */
suspend fun AtlasClient.billingCheck(feature: String, required: Int? = null, orgId: String? = null): BillingFeatureCheck {
    val j = authedRequest("GET", billingPath("check", "feature" to feature, "required" to required?.toString(), "org_id" to orgId), null)
    return BillingFeatureCheck(
        feature = j.string("feature") ?: feature,
        allowed = j.bool("allowed") ?: false,
        source = j.string("source"),
        remaining = j.flex("remaining"),
        granted = j.flex("granted"),
        usage = j.flex("usage"),
        unlimited = j.bool("unlimited") ?: false,
    )
}

/** Would this discount code work for this plan? For a checkout form's apply button. */
suspend fun AtlasClient.billingValidateCoupon(planId: String, code: String, orgId: String? = null): BillingCouponCheck {
    val j = authedRequest(
        "POST",
        "/v1/client/billing/coupons/validate",
        jsonBody("code" to jstr(code.trim()), "plan_id" to jstr(planId), "org_id" to jstr(orgId)),
    )
    val d = j.obj("discount")
    return BillingCouponCheck(
        valid = j.bool("valid") ?: false,
        reason = j.string("reason"),
        percentOff = d?.flex("percent_off"),
        amountOff = d?.flex("amount_off"),
        currency = d?.string("currency"),
        duration = d?.string("duration"),
        durationMonths = d?.number("duration_months")?.toInt(),
    )
}
