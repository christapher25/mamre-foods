package com.mamre.billing.data

import com.mamre.billing.data.db.CustomerDao
import com.mamre.billing.data.db.CustomerEntity
import com.mamre.billing.data.db.CustomerTypeDao
import com.mamre.billing.data.db.CustomerTypeEntity
import com.mamre.billing.data.db.PriceDefaultDao
import com.mamre.billing.data.db.PriceDefaultEntity
import com.mamre.billing.data.db.PriceOverrideDao
import com.mamre.billing.data.db.PriceOverrideEntity
import com.mamre.billing.data.db.ProductDao
import com.mamre.billing.data.db.ProductEntity
import com.mamre.billing.data.db.SettingDao
import com.mamre.billing.data.db.SettingEntity
import com.mamre.billing.data.db.SyncStateDao
import com.mamre.billing.data.db.SyncStateEntity
import com.mamre.billing.data.db.TransactionRunner

// In-memory DAO fakes for JVM tests (owner decision: no Robolectric). They mimic
// @Upsert by id. Real Room queries are covered by a later instrumented phase.

class FakeProductDao : ProductDao {
    val rows = linkedMapOf<String, ProductEntity>()
    override suspend fun upsertAll(rows: List<ProductEntity>) {
        rows.forEach { this.rows[it.id] = it }
    }
    override suspend fun getAll() = rows.values.sortedBy { it.name }
    override suspend fun getActive() = getAll().filter { it.isActive }
}

class FakeCustomerTypeDao : CustomerTypeDao {
    val rows = linkedMapOf<String, CustomerTypeEntity>()
    override suspend fun upsertAll(rows: List<CustomerTypeEntity>) {
        rows.forEach { this.rows[it.id] = it }
    }
    override suspend fun getAll() = rows.values.sortedBy { it.name }
    override suspend fun getActive() = getAll().filter { it.isActive }
}

class FakeCustomerDao : CustomerDao {
    val rows = linkedMapOf<String, CustomerEntity>()
    override suspend fun upsertAll(rows: List<CustomerEntity>) {
        rows.forEach { this.rows[it.id] = it }
    }
    override suspend fun getAll() = rows.values.sortedBy { it.name }
    override suspend fun getActive() = getAll().filter { it.isActive }
    override suspend fun getById(id: String) = rows[id]
}

class FakePriceDefaultDao : PriceDefaultDao {
    val rows = linkedMapOf<String, PriceDefaultEntity>()
    override suspend fun upsertAll(rows: List<PriceDefaultEntity>) {
        rows.forEach { this.rows[it.id] = it }
    }
    override suspend fun getAll() = rows.values.toList()
}

class FakePriceOverrideDao : PriceOverrideDao {
    val rows = linkedMapOf<String, PriceOverrideEntity>()
    override suspend fun upsertAll(rows: List<PriceOverrideEntity>) {
        rows.forEach { this.rows[it.id] = it }
    }
    override suspend fun getAll() = rows.values.toList()
}

class FakeSyncStateDao : SyncStateDao {
    val rows = linkedMapOf<String, Long>()
    override suspend fun get(key: String) = rows[key]
    override suspend fun put(row: SyncStateEntity) {
        rows[row.key] = row.value
    }
}

class FakeTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class FakeSettingDao : SettingDao {
    val rows = linkedMapOf<String, String>()
    override suspend fun upsertAll(rows: List<SettingEntity>) {
        rows.forEach { this.rows[it.key] = it.value }
    }
    override suspend fun getAll() = rows.map { SettingEntity(it.key, it.value) }
}
