package com.mamre.billing.data.local

/**
 * First run (Doc 2 s5.2): REFERENCE DATA ONLY. The four customer types with their price-edit switches (Retail and
 * Catering on), the two products (standard packet 12, yield 32), the materials list, the recipe per 1 kg of wheat
 * (wheat 1 kg, oil 80 ml, sugar 20 g, salt 15 g, baking powder 2 g, packing one piece per packet; potassium sorbate
 * unset for Mamre Chapathi, Doc 1 P-2), the expense categories (Doc 1 s10.2) and the default settings. No customers,
 * prices, bills or demo data (AT-21). Written in one transaction; every insert ignores a row that exists, so running it
 * again changes nothing and never overwrites what the Owner edited.
 */
class ReferenceSeed(
    private val db: MamreDatabase,
    private val unitOfWork: UnitOfWork,
) {
    suspend fun run() {
        if (db.customerTypeDao().count() > 0 && db.settingDao().get(SettingKeys.DEVICE_CODE) != null) return
        unitOfWork.run {
            db.customerTypeDao().insertAll(customerTypes)
            db.productDao().insertAll(products)
            db.materialDao().insertAll(materials)
            db.recipeDao().insertAll(recipe)
            db.expenseCategoryDao().insertAll(expenseCategories)
            db.settingDao().insertAll(settings)
        }
    }

    companion object {
        const val DEFAULT_DEVICE_CODE = "W1"
        const val DEFAULT_BUSINESS_NAME = "Mamre Foods"
        const val DEFAULT_WASTAGE_BP = 200
        const val DEFAULT_LOCK_MINUTES = 5
        const val STANDARD_PACKET_SIZE = 12
        const val YIELD_PER_KG = 32

        val customerTypes = listOf(
            CustomerTypeEntity(ReferenceIds.TYPE_RESTAURANT, "Restaurant", salesmanCanEditPrice = false, isActive = true),
            CustomerTypeEntity(ReferenceIds.TYPE_SHOP, "Shop", salesmanCanEditPrice = false, isActive = true),
            CustomerTypeEntity(ReferenceIds.TYPE_RETAIL, "Retail", salesmanCanEditPrice = true, isActive = true),
            CustomerTypeEntity(ReferenceIds.TYPE_CATERING, "Catering", salesmanCanEditPrice = true, isActive = true),
        )

        // The product codes are placeholders until the owner gives the real ones (QUESTIONS 2026-10-02, A1).
        val products = listOf(
            ProductEntity(ReferenceIds.PRODUCT_FRESH, "FRESH", "Mamre Fresh Chapathi", STANDARD_PACKET_SIZE, YIELD_PER_KG, true),
            ProductEntity(ReferenceIds.PRODUCT_CHAPATHI, "CHAPATHI", "Mamre Chapathi", STANDARD_PACKET_SIZE, YIELD_PER_KG, true),
        )

        private fun material(id: String, name: String, base: String, purchase: String, basePerPurchase: Long, packing: Boolean = false) =
            MaterialEntity(id, name, base, purchase, basePerPurchase, packing)

        val materials = listOf(
            material(ReferenceIds.MATERIAL_WHEAT, "Whole wheat flour", "g", "kg", 1_000),
            material(ReferenceIds.MATERIAL_OIL, "Oil", "ml", "L", 1_000),
            material(ReferenceIds.MATERIAL_SUGAR, "Sugar", "g", "kg", 1_000),
            material(ReferenceIds.MATERIAL_SALT, "Salt", "g", "kg", 1_000),
            material(ReferenceIds.MATERIAL_BAKING_POWDER, "Baking powder", "g", "kg", 1_000),
            material(ReferenceIds.MATERIAL_SORBATE, "Potassium sorbate", "g", "kg", 1_000),
            material(ReferenceIds.MATERIAL_PACKING, "Packing", "piece", "piece", 1, packing = true),
        )

        /** Quantity per 1 kg of wheat in milli-units of the base unit (1,000 = 1 g, 1 ml or 1 piece). */
        private val baseRecipe = listOf(
            ReferenceIds.MATERIAL_WHEAT to 1_000_000L,
            ReferenceIds.MATERIAL_OIL to 80_000L,
            ReferenceIds.MATERIAL_SUGAR to 20_000L,
            ReferenceIds.MATERIAL_SALT to 15_000L,
            ReferenceIds.MATERIAL_BAKING_POWDER to 2_000L,
        )

        /** One packing piece per packet, whatever the packet size (Doc 1 s9.2). */
        private const val PACKING_PIECE_MILLI = 1_000L

        val recipe: List<RecipeItemEntity> = listOf(ReferenceIds.PRODUCT_FRESH, ReferenceIds.PRODUCT_CHAPATHI).flatMap { product ->
            val lines = baseRecipe.toMutableList<Pair<String, Long?>>()
            // Potassium sorbate only on Mamre Chapathi, with the quantity still unset (Doc 1 P-2).
            if (product == ReferenceIds.PRODUCT_CHAPATHI) lines += ReferenceIds.MATERIAL_SORBATE to null
            lines += ReferenceIds.MATERIAL_PACKING to PACKING_PIECE_MILLI
            lines.map { (material, qty) -> RecipeItemEntity("$product:$material", product, material, qty) }
        }

        val expenseCategories = listOf(
            ExpenseCategoryEntity(ReferenceIds.CATEGORY_ELECTRICITY, "Electricity", Stored.INDIRECT),
            ExpenseCategoryEntity(ReferenceIds.CATEGORY_WATER, "Water", Stored.INDIRECT),
            ExpenseCategoryEntity(ReferenceIds.CATEGORY_MACHINE, "Machine", Stored.INDIRECT),
            ExpenseCategoryEntity(ReferenceIds.CATEGORY_LABOUR, "Labour", Stored.INDIRECT),
            ExpenseCategoryEntity(ReferenceIds.CATEGORY_MAINTENANCE, "Maintenance", Stored.INDIRECT),
            ExpenseCategoryEntity(ReferenceIds.CATEGORY_DELIVERY, "Delivery charge", Stored.INDIRECT),
            ExpenseCategoryEntity(ReferenceIds.CATEGORY_FUEL, "Fuel", Stored.INDIRECT),
            ExpenseCategoryEntity(ReferenceIds.CATEGORY_OTHER, "Other", Stored.INDIRECT),
        )

        /** Business name, device code, wastage 2%, lock after 5 minutes (Doc 2 s5.2) and the two sequences. */
        val settings = listOf(
            SettingEntity(SettingKeys.BUSINESS_NAME, DEFAULT_BUSINESS_NAME),
            SettingEntity(SettingKeys.DEVICE_CODE, DEFAULT_DEVICE_CODE),
            SettingEntity(SettingKeys.WASTAGE_BP, DEFAULT_WASTAGE_BP.toString()),
            SettingEntity(SettingKeys.LOCK_MINUTES, DEFAULT_LOCK_MINUTES.toString()),
            SettingEntity(SettingKeys.NEXT_BILL_SEQ, "1"),
            SettingEntity(SettingKeys.NEXT_RECEIPT_SEQ, "1"),
        )
    }
}
