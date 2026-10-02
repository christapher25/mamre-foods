package com.mamre.billing.ui.worker

import com.mamre.billing.domain.auth.Routes

/** Destinations of the worker graph. Every one lives under "worker/" so the guard can see it. */
object WorkerRoutes {
    const val HOME = Routes.WORKER_HOME
    const val SYNC = "worker/sync"
    const val TODAY = "worker/today"
    const val SOON = "worker/soon/{tile}"

    fun soon(tile: HomeTile) = "worker/soon/${tile.name}"

    // New invoice: W2 to W5 share one ViewModel scoped to this nested graph.
    const val INVOICE_GRAPH = "worker/invoice"
    const val INVOICE_CUSTOMER = "worker/invoice/customer"
    const val INVOICE_BUILD = "worker/invoice/build"
    const val INVOICE_PAYMENT = "worker/invoice/payment"
    const val INVOICE_CONFIRM = "worker/invoice/confirm"

    // Record payment (W7).
    const val PAYMENT_GRAPH = "worker/payment"
    const val PAYMENT_CUSTOMER = "worker/payment/customer"
    const val PAYMENT_FORM = "worker/payment/form"

    // Return (W8).
    const val RETURN_GRAPH = "worker/return"
    const val RETURN_CUSTOMER = "worker/return/customer"
    const val RETURN_FORM = "worker/return/form"
    const val RETURN_DONE = "worker/return/done"

    // W6 Bill and the payment receipt preview: one screen, loaded by kind and id.
    const val ARG_KIND = "kind"
    const val ARG_ID = "id"
    const val ARG_DUPLICATE = "duplicate"
    const val KIND_INVOICE = "invoice"
    const val KIND_PAYMENT = "payment"
    const val RECEIPT = "worker/receipt/{$ARG_KIND}/{$ARG_ID}?$ARG_DUPLICATE={$ARG_DUPLICATE}"

    fun receipt(kind: String, id: String, duplicate: Boolean = false) =
        "worker/receipt/$kind/$id?$ARG_DUPLICATE=$duplicate"
}
