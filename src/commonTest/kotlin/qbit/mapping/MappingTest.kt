package qbit.mapping

import qbit.*
import qbit.model.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty0
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

data class Addr(val id: Long?, val addr: String)

data class User(
        val id: Long? = null,
        val login: String,
        val strs: List<String>,
        val addr: Addr,
        val optAddr: Addr?,
        val addrs: List<Addr>
)

class MappingTest {

    @Test
    fun test() {
        val eids = EID(0, 0).nextEids()

        val testSchema = schema {
            entity(User::class) {
                unique(it::login)
            }
            entity(Addr::class)
        }

        val db = IndexDb(Index().addFacts(testSchema.flatMap { destruct(it, emptyDb, eids) }), nullHash)

        val user = User(
                login = "login",
                strs = listOf("str1", "str2"),
                addr = Addr(null, "addr"),
                optAddr = Addr(null, "optAddr"),
                addrs = listOf(Addr(null, "lstAddr"))
        )

        val facts = destruct(user, db, eids)
        val db2 = IndexDb(db.index.addFacts(facts), nullHash)
        val u = reconstruct(User::class, facts.filter { it.eid.eid == 6}, db2)
        assertEquals("login", u.login)
        assertEquals(listOf("str1", "str2"), u.strs)
        assertEquals("addr", u.addr.addr)
        assertNull(u.optAddr)
        assertEquals("lstAddr", u.addrs[0].addr)

        val fullUser = reconstruct(Query(User::class, mapOf(User::optAddr.name to null)), facts.filter { it.eid.eid == 6 }, db2)
        assertEquals("optAddr", fullUser.optAddr!!.addr)

        schema {
            entity(User::class) {
                unique(it::login)
            }
        }
    }


}

private fun schema(body: SchemaBuilder.() -> Unit): List<Attr2> {
    val scb = SchemaBuilder()
    scb.body()
    return scb.attrs
}

class SchemaBuilder {

    internal val attrs: MutableList<Attr2> = ArrayList()

    fun <T : Any> entity(type: KClass<T>, body: EntityBuilder.(T) -> Unit = {}) {
        val eb = EntityBuilder(type)
        eb.body(default(type))
        attrs.addAll(schemaFor(type, eb.uniqueProps))
    }

}

class EntityBuilder(internal val type: KClass<*>) {

    internal val uniqueProps = HashSet<String>()

    fun unique(prop: KProperty0<*>) {
        uniqueProps.add(type.attrName(prop))
    }

}

val defaults = hashMapOf<KClass<*>, Any>()

fun <T : Any> default(type: KClass<T>): T =
        defaults.getOrPut(type) {
            when (type) {
                Boolean::class -> false as T
                String::class -> "" as T
                Byte::class -> 0.toByte() as T
                Long::class -> 0L as T
                List::class -> listOf<Any>() as T
                else -> {
                    val constr = type.constructors.first()
                    val args = constr.parameters.map { it to default(it.type.classifier as KClass<*>) }.toMap()
                    constr.callBy(args)
                }
            }
        } as T
