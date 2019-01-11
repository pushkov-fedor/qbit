package qbit.serialization

import qbit.*
import java.io.EOFException
import java.io.InputStream
import java.nio.CharBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object SimpleSerialization : Serialization {

    private val nullNode = NodeRef(nullHash)

    override fun serializeNode(n: NodeVal<Hash?>): ByteArray {
        return when (n) {
            is Root -> serializeNode(nullNode, nullNode, n.source, n.timestamp, n.data)
            is Leaf -> serializeNode(nullNode, n.parent, n.source, n.timestamp, n.data)
            is Merge -> serializeNode(n.parent1, n.parent2, n.source, n.timestamp, n.data)
        }
    }

    override fun serializeNode(parent1: Node<Hash>, parent2: Node<Hash>, source: DbUuid, timestamp: Long, data: NodeData) = serialize(parent1, parent2, source, timestamp, data)

    override fun deserializeNode(ins: InputStream): NodeVal<Hash?> {
        val parent1 = Hash(deserialize(ins, QBytes) as ByteArray)
        val parent2 = Hash(deserialize(ins, QBytes) as ByteArray)
        val iid = deserialize(ins, QInt) as Int
        val instanceBits = deserialize(ins, QByte) as Byte
        val timestamp = deserialize(ins, QLong) as Long
        val factsCount = deserialize(ins, QInt) as Int
        val facts = (1..factsCount).asSequence().map {
            val eid = deserialize(ins, QEID) as EID
            val attr = deserialize(ins, QString) as String
            val value = deserialize(ins)
            val deleted = deserialize(ins, QBoolean) as Boolean
            Fact(eid, attr, value, deleted)
        }
        val nodeData = NodeData(facts.toList().toTypedArray())
        return when {
            parent1 == nullHash && parent2 == nullHash -> Root(null, DbUuid(IID(iid, instanceBits)), timestamp, nodeData)
            parent1 == nullHash && parent2 != nullHash -> Leaf(null, NodeRef(parent2), DbUuid(IID(iid, instanceBits)), timestamp, nodeData)
            parent1 != nullHash && parent2 != nullHash -> Merge(null, NodeRef(parent1), NodeRef(parent2), DbUuid(IID(iid, instanceBits)), timestamp, nodeData)
            else -> throw DeserializationException("Corrupted node data: parent1: $parent1, parent2: $parent2")
        }
    }
}

// Serialization

internal fun serialize(vararg anys: Any): ByteArray {
    val bytes = anys.map { a ->
        when (a) {
            is Node<*> -> serialize(a.hash!!.bytes)
            is DbUuid -> byteArray(serialize(a.iid.value), serialize(a.iid.instanceBits))
            is Boolean -> byteArray(QBoolean.code, if (a) 1.toByte() else 0.toByte())
            is Byte -> byteArray(QByte.code, a)
            is Int -> byteArray(QInt.code, serializeInt(a))
            is Long -> byteArray(QLong.code, serializeLong(a))
            is String -> byteArray(QString.code, byteArray(a))
            is NodeData -> byteArray(serialize(a.trx.size), *a.trx.map { serialize(it) }.toTypedArray())
            is Fact -> serialize(a.eid, a.attr, a.value, a.deleted)
            is EID -> byteArray(QEID.code, serializeLong(a.value()))
            is ByteArray -> byteArray(QBytes.code, serializeInt(a.size), a)
            is Instant -> byteArray(QInstant.code, serializeLong(a.toEpochMilli()))
            is ZonedDateTime -> byteArray(QZonedDateTime.code, serializeLong(a.toInstant().toEpochMilli() / 1000), serializeInt(a.toInstant().nano), byteArray(a.zone.id))
            else -> throw AssertionError("Should never happen, a is $a")
        }
    }
    return byteArray(*bytes.toTypedArray())
}

private fun byteArray(str: String): ByteArray =
        byteArray(serializeInt(str.toByteArray(Charsets.UTF_8).size), str.toByteArray(Charsets.UTF_8))

private fun toBytes(c: Char): ByteArray =
        Charsets.UTF_8.encode(CharBuffer.wrap(charArrayOf(c))).array()

private val coder = HashMap<Char, ByteArray>()

internal fun byteArray(vararg parts: Any): ByteArray {
    val res = ByteArray(parts.sumBy { size(it) })
    var idx = 0
    for (part in parts) {
        when (part) {
            is ByteArray -> {
                System.arraycopy(part, 0, res, idx, part.size)
                idx += part.size
            }
            is Byte -> {
                res[idx++] = part
            }
            is Char -> {
                val bytes = coder.getOrPut(part) { toBytes(part) }
                System.arraycopy(bytes, 0, res, idx, bytes.size)
                idx += bytes.size
            }
        }
    }
    return res
}

internal fun size(v: Any): Int = when (v) {
    is ByteArray -> v.size
    is Byte -> 1
    is Char -> String(charArrayOf(v)).toByteArray().size
    else -> throw AssertionError("Should never happen, v is $v")
}

internal fun serializeInt(a: Int): ByteArray = byteArrayOf(byteOf(3, a), byteOf(2, a), byteOf(1, a), byteOf(0, a))

internal fun byteOf(idx: Int, i: Int) = i.shr(8 * idx).and(0xFF).toByte()

internal fun serializeLong(a: Long) = byteArrayOf(byteOf(7, a), byteOf(6, a), byteOf(5, a), byteOf(4, a),
        byteOf(3, a), byteOf(2, a), byteOf(1, a), byteOf(0, a))

internal fun byteOf(idx: Int, i: Long) = i.shr(8 * idx).and(0xFF).toByte()


// Deserialization

internal fun <T : Any> deserialize(ins: InputStream, mark: DataType<T>): Any {
    val byte = ins.read()
    when {
        byte == -1 -> throw EOFException("Unexpected end of input")
        byte.toByte() != mark.code -> throw DeserializationException("Code is $byte while $mark expected")
        DataType.ofCode(byte.toByte()) == null -> throw DeserializationException("Unknown mark: ${byte.toChar()}")
    }
    return readMark(ins, mark)
}

internal fun deserialize(ins: InputStream): Any {
    val byte = ins.read()
    val mark: DataType<out Any> = when (byte) {
        -1 -> throw DeserializationException(cause = EOFException())
        else -> (DataType.ofCode(byte.toByte())) ?: throw DeserializationException("Unknown mark: ${byte.toChar()}")
    }
    return readMark(ins, mark)
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> readMark(ins: InputStream, expectedMark: DataType<T>): Any {
    return when (expectedMark) {
        QBoolean -> (ins.read().toByte() == 1.toByte()) as T
        QByte -> ins.read().toByte() as T
        QInt -> readInt(ins) as T
        QLong -> readLong(ins) as T
        QInstant -> Instant.ofEpochMilli(readLong(ins)) as T

        QZonedDateTime -> {
            val instant = Instant.ofEpochSecond(readLong(ins), readInt(ins).toLong())
            val zone = String(readBytes(ins, readInt(ins)), Charsets.UTF_8)
            ZonedDateTime.ofInstant(instant, ZoneId.of(zone)) as T
        }

        QBytes -> readInt(ins).let { count ->
            readBytes(ins, count) as T
        }

        QString -> readInt(ins).let { count ->
            String(readBytes(ins, count), Charsets.UTF_8) as T
        }
        QEID -> EID(readLong(ins)) as T
        QEntity -> throw AssertionError("Should never happen")
        is QList<*> -> throw AssertionError("Should never happen")
    }
}

internal fun readBytes(ins: InputStream, count: Int): ByteArray {
    val buff = ByteArray(count)
    val len = ins.read(buff)
    if (len < count) {
        throw DeserializationException(cause = EOFException("There are not enough bytes in stream"))
    } else {
        return buff
    }
}

internal fun readInt(ins: InputStream): Int = readNumber(ins, 4) { buff ->
    buff[0].toInt().and(0xFF).shl(8 * 3) or buff[1].toInt().and(0xFF).shl(8 * 2) or
            buff[2].toInt().and(0xFF).shl(8) or buff[3].toInt().and(0xFF)
}

internal fun readLong(ins: InputStream): Long = readNumber(ins, 8) { buff ->
    buff[0].toLong().and(0xFF).shl(8 * 7) or buff[1].toLong().and(0xFF).shl(8 * 6) or
            buff[2].toLong().and(0xFF).shl(8 * 5) or buff[3].toLong().and(0xFF).shl(8 * 4) or
            buff[4].toLong().and(0xFF).shl(8 * 3) or buff[5].toLong().and(0xFF).shl(8 * 2) or
            buff[6].toLong().and(0xFF).shl(8) or buff[7].toLong().and(0xFF)
}

internal fun <T> readNumber(ins: InputStream, size: Int, parse: (ByteArray) -> T): T {
    val buff = ByteArray(size)
    val len = ins.read(buff)
    if (len < size) {
        throw EOFException("There are not enough bytes in stream")
    } else {
        return parse(buff)
    }
}
