package com.mamre.billing.ui.worker

import com.mamre.billing.domain.auth.Routes

/** Destinations of the worker graph. Every one lives under "sales/" so the guard can see it. */
object WorkerRoutes {
    const val HOME = Routes.SALES_HOME
    const val TODAY = "sales/today"

    // New invoice: W2 to W5 share one ViewModel scoped to this nested graph.
    const val INVOICE_GRAPH = "sales/invoice"
    const val INVOICE_CUSTOMER = "sales/invoice/customer"
    const val INVOICE_BUILD = "sales/invoice/build"
    const val INVOICE_PAYMENT = "sales/invoice/payment"
    const val INVOICE_CONFIRM = "sales/invoice/confirm"

    // Record payment (W7).
    const val PAYMENT_GRAPH = "sales/payment"
    const val PAYMENT_CUSTOMER = "sales/payment/customer"
    const val PAYMENT_FORM = "sales/payment/form"

    // Return (W8).
    const val RETURN_GRAPH = "sales/return"
    const val RETURN_CUSTOMER = "sales/return/customer"
    const val RETURN_FORM = "sales/return/form"
    const val RETURN_DONE = "sales/return/done"

    // W6 Bill and the payment receipt preview: one screen, loaded by kind and id.
    const val ARG_KIND = "kind"
    const val ARG_ID = "id"
    const val ARG_DUPLICATE = "duplicate"
    const val KIND_INVOICE = "invoice"
    const val KIND_PAYMENT = "payment"
    const val RECEIPT = "sales/receipt/{$ARG_KIND}/{$ARG_ID}?$ARG_DUPLICATE={$ARG_DUPLICATE}"

    fun receipt(kind: String, id: String, duplicate: Boolean = false) =
        "sales/receipt/$kind/$id?$ARG_DUPLICATE=$duplicate"
}
