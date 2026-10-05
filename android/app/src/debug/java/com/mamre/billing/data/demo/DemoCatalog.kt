package com.mamre.billing.data.demo

import com.mamre.billing.data.local.ReferenceIds
import com.mamre.billing.domain.admin.BusinessSettings
import com.mamre.billing.domain.model.PaymentMode
import java.time.LocalDate
import java.time.YearMonth

// DEBUG ONLY, DEMO DATA. The invented customers, selling prices, override prices and business details the demo history is
// made from (Doc 1 P-4, P-6, P-8 are pending: none of this is real). It is written into the real database by DemoSeeder
// through the use cases, so every rule applies to it. Reference data (types, products, materials, recipe) comes from
// ReferenceSeed and is not repeated here.

/** Ids of the demo customers. */
object DemoCustomerIds {
    const val SPICE_GARDEN = "00000000-0000-4000-8000-0000000000c1"
    const val PATEL_MART = "00000000-0000-4000-8000-0000000000c2"
    const val RAO_FAMILY = "00000000-0000-4000-8000-0000000000c3"
    const val ROYAL_BANQUETS = "00000000-0000-4000-8000-0000000000c4"
    const val FRESHMART_DOWNTOWN = "00000000-0000-4000-8000-0000000000c5"
    const val FRESHMART_WESTSIDE = "00000000-0000-4000-8000-0000000000c6"
    const val CURRY_HOUSE = "c-curry-house"
    const val TAJ_KITCHEN = "c-taj-kitchen"
    const val MASALA_BISTRO = "c-masala-bistro"
    const val CORNER_SHOP = "c-corner-shop"
    const val DESI_GROCERS = "c-desi-grocers"
    const val SHARMA_FAMILY = "c-sharma-family"
}

data class DemoCustomer(
    val id: String,
    val name: String,
    val location: String,
    val typeId: String,
    val mode: PaymentMode,
    val corporate: Boolean = false,
)

data class DemoPrice(val productId: String, val typeId: String, val cents: Long, val from: LocalDate)

data class DemoOverride(val customerId: String, val productId: String, val cents: Long, val from: LocalDate, val note: String)

object DemoCatalog {
    /** The name the demo bills print after Salesman. */
    const val OWNER_NAME = "Rajesh"

    val customers: List<DemoCustomer> = listOf(
        DemoCustomer(DemoCustomerIds.SPICE_GARDEN, "Spice Garden", "Irving", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CREDIT),
        DemoCustomer(DemoCustomerIds.CURRY_HOUSE, "Curry House", "Plano", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CREDIT),
        DemoCustomer(DemoCustomerIds.TAJ_KITCHEN, "Taj Kitchen", "Frisco", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CREDIT),
        DemoCustomer(DemoCustomerIds.MASALA_BISTRO, "Masala Bistro", "Allen", ReferenceIds.TYPE_RESTAURANT, PaymentMode.CASH),
        DemoCustomer(DemoCustomerIds.PATEL_MART, "Patel Mart", "Mesquite", ReferenceIds.TYPE_SHOP, PaymentMode.CREDIT),
        DemoCustomer(DemoCustomerIds.CORNER_SHOP, "Corner Shop", "Richardson", ReferenceIds.TYPE_SHOP, PaymentMode.CREDIT),
        DemoCustomer(DemoCustomerIds.DESI_GROCERS, "Desi Grocers", "Garland", ReferenceIds.TYPE_SHOP, PaymentMode.CASH),
        DemoCustomer(DemoCustomerIds.RAO_FAMILY, "Rao Family", "Coppell", ReferenceIds.TYPE_RETAIL, PaymentMode.CASH),
        DemoCustomer(DemoCustomerIds.SHARMA_FAMILY, "Sharma Family", "Lewisville", ReferenceIds.TYPE_RETAIL, PaymentMode.CREDIT),
        DemoCustomer(DemoCustomerIds.ROYAL_BANQUETS, "Royal Banquets", "Addison", ReferenceIds.TYPE_CATERING, PaymentMode.CREDIT),
        // Two stores of one chain: the same name, told apart by location (Doc 1 s4.1).
        DemoCustomer(DemoCustomerIds.FRESHMART_DOWNTOWN, "FreshMart", "Downtown", ReferenceIds.TYPE_SHOP, PaymentMode.CREDIT, corporate = true),
        DemoCustomer(DemoCustomerIds.FRESHMART_WESTSIDE, "FreshMart", "Westside", ReferenceIds.TYPE_SHOP, PaymentMode.CREDIT, corporate = true),
    )

    /** Selling prices per standard packet, history included. The older price makes the price history more than one row. */
    fun prices(today: LocalDate): List<DemoPrice> {
        val priceStart = today.minusMonths(12).withDayOfMonth(1)
        val older = today.minusMonths(18).withDayOfMonth(1)
        val fresh = ReferenceIds.PRODUCT_FRESH
        val chapathi = ReferenceIds.PRODUCT_CHAPATHI
        return listOf(
            DemoPrice(fresh, ReferenceIds.TYPE_RESTAURANT, 270, older),
            DemoPrice(fresh, ReferenceIds.TYPE_RESTAURANT, 280, priceStart),
            DemoPrice(chapathi, ReferenceIds.TYPE_RESTAURANT, 250, priceStart),
            DemoPrice(fresh, ReferenceIds.TYPE_SHOP, 300, priceStart),
            DemoPrice(chapathi, ReferenceIds.TYPE_SHOP, 270, priceStart),
            DemoPrice(fresh, ReferenceIds.TYPE_RETAIL, 350, priceStart),
            DemoPrice(chapathi, ReferenceIds.TYPE_RETAIL, 320, priceStart),
            DemoPrice(fresh, ReferenceIds.TYPE_CATERING, 260, priceStart),
            DemoPrice(chapathi, ReferenceIds.TYPE_CATERING, 230, priceStart),
        )
    }

    fun overrides(today: LocalDate): List<DemoOverride> {
        val first = YearMonth.from(today).minusMonths(6)
        val start = first.atDay(1)
        val launch = first.plusMonths(2).atDay(1) // Mamre Chapathi is sold from the third month of the data
        return listOf(
            DemoOverride(DemoCustomerIds.SPICE_GARDEN, ReferenceIds.PRODUCT_CHAPATHI, 240, launch, "Volume customer"),
            DemoOverride(DemoCustomerIds.PATEL_MART, ReferenceIds.PRODUCT_FRESH, 285, start, "Agreed at signup"),
        )
    }

    /** Obviously fake business details, only ever in the debug build (Doc 1 A-31: the real ones come from Settings). */
    val settings = BusinessSettings("MAMRE FOODS", "123 Example Street\nAnytown, TX 00000", "+1 (000) 000-0000", "Thank you!")
}
