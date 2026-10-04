package com.mamre.billing.data.local

/**
 * Fixed UUIDs of the reference rows (Doc 2 s5.2): the four customer types, the two products, the seven materials and the expense categories.
 * They are the same on every phone, so the first-run seed is idempotent, the books can recognise potassium sorbate
 * (Doc 1 P-2) and version 2 can import the export without remapping. They are ids, not business details.
 */
object ReferenceIds {
    const val TYPE_RESTAURANT = "4f4c206c-af51-4661-8bbe-992c9be877a8"
    const val TYPE_SHOP = "3ac79088-97ef-47cb-91cb-d58cd46cc031"
    const val TYPE_RETAIL = "ef93ef4e-9143-450c-865f-4c5d820580b7"
    const val TYPE_CATERING = "5d5f94e4-e814-43b9-be67-902032674c67"

    const val PRODUCT_FRESH = "1d7f3e73-8aa5-441a-8cc3-a5cb4f38bc9f"
    const val PRODUCT_CHAPATHI = "b5a81ae6-b56b-47a4-a2ae-72b62e7922b4"

    const val MATERIAL_WHEAT = "4adbee86-862e-4cd3-a793-132ca04d1ced"
    const val MATERIAL_OIL = "c2d2b3ec-e024-40a0-bf73-bef2c23e3602"
    const val MATERIAL_SUGAR = "beb05994-d10f-4064-8604-52936ab9a758"
    const val MATERIAL_SALT = "27e82f22-fa8e-4582-826e-c4e219aa1f8d"
    const val MATERIAL_BAKING_POWDER = "2073bc28-6a99-4cf4-9e2f-2b70bb56a9d3"
    const val MATERIAL_SORBATE = "6ecbe747-259c-4810-ab31-686d13b1057b"
    const val MATERIAL_PACKING = "90fd6ca4-60d5-4387-a984-b92731f835fc"

    // Expense categories (Doc 1 s10.2).
    const val CATEGORY_ELECTRICITY = "231f532d-af0a-48eb-97ac-642764a1b0e2"
    const val CATEGORY_WATER = "a39890d4-0749-4f46-807d-806c0e4c4dd8"
    const val CATEGORY_MACHINE = "6b4cb390-2fb3-426f-a09c-4394c7313ea0"
    const val CATEGORY_LABOUR = "0b148aae-b8ca-4cc1-a794-7f803ca68471"
    const val CATEGORY_MAINTENANCE = "747d749e-9124-4b19-832a-e629325af70b"
    const val CATEGORY_DELIVERY = "c7e6f05b-a680-4ca9-b8dd-6b0fe86ab824"
    const val CATEGORY_FUEL = "f480d0b0-b346-4eae-bffa-0ed9386f6018"
    const val CATEGORY_OTHER = "3027662a-e681-406b-9bbc-c64baa1b3aae"
}
