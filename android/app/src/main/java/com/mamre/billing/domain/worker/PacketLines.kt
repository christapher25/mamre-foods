package com.mamre.billing.domain.worker

// Packets and custom packets (change set C2). A standard packet holds a per-product number of chapathis (6);
// a custom packet holds 1 to 200. Money is Long cents and all rounding is half up in integer arithmetic.

const val DEFAULT_PACKET_SIZE = 6
const val MAX_PACKET_SIZE = 200

fun isValidPacketSize(chapathis: Int): Boolean = chapathis in 1..MAX_PACKET_SIZE

/** One kind of packet on an invoice: a product and how many chapathis each packet holds. */
data class PacketKey(val productId: String, val chapathisPerPacket: Int)

/** How many packets of one kind were entered; [chargedCents] is a worker price change (change set C3), null for the list price. */
data class PacketEntry(val key: PacketKey, val qtyPackets: Int, val chargedCents: Long? = null)

/**
 * Default price of a packet of [chapathis]: round half up(standard packet price x chapathis / standard size),
 * integer arithmetic only. For the standard size the result is the list price itself.
 */
fun customPacketPriceCents(standardPriceCents: Long, chapathis: Int, standardSize: Int): Long {
    require(standardSize > 0) { "the standard packet holds at least one chapathi" }
    val numerator = standardPriceCents * chapathis
    return (numerator * 2 + standardSize) / (standardSize * 2L)
}

/**
 * The invoice lines for the packets entered (Doc 1 s5.1). Standard lines come first, then custom ones by size,
 * grouped by product in the order given. A product with no price never becomes a line, and neither does a size
 * outside 1 to 200 or a quantity of zero: a missing price is not zero (Doc 3 N5). A price change is applied only
 * when [priceEditAllowed] (the customer type's flag), so a non-editable type cannot change a price even if the
 * screen is bypassed.
 */
fun buildPacketLines(
    products: List<PricedProduct>,
    entries: List<PacketEntry>,
    priceEditAllowed: Boolean = false,
): List<InvoiceLine> {
    val byId = products.associateBy { it.productId }
    val order = products.withIndex().associate { it.value.productId to it.index }
    return entries
        .filter { it.qtyPackets > 0 && isValidPacketSize(it.key.chapathisPerPacket) && it.key.productId in byId }
        .sortedWith(
            compareBy<PacketEntry>(
                { order.getValue(it.key.productId) },
                { byId.getValue(it.key.productId).standardPacketSize != it.key.chapathisPerPacket },
                { it.key.chapathisPerPacket },
            ),
        )
        .mapNotNull { e ->
            val p = byId.getValue(e.key.productId)
            val standardPrice = p.unitPriceCents ?: return@mapNotNull null
            val size = e.key.chapathisPerPacket
            val list = customPacketPriceCents(standardPrice, size, p.standardPacketSize)
            val charged = e.chargedCents?.takeIf { priceEditAllowed && it > 0 } ?: list
            InvoiceLine(
                productId = p.productId,
                productName = p.name,
                qtyPackets = e.qtyPackets,
                unitPriceCents = charged,
                chapathisPerPacket = size,
                listPriceCents = list,
                isCustomPacket = size != p.standardPacketSize,
            )
        }
}

/** Chapathis sold by an invoice: packets x chapathis per packet, summed over its lines. */
fun chapathisSold(lines: List<InvoiceLine>): Int = lines.sumOf { it.chapathis }
