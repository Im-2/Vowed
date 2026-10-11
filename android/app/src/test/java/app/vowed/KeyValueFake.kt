package app.vowed.wallet

import app.vowed.data.KeyValue

/** an in-memory KeyValue for tests */
class KeyValueFake : KeyValue {
    private val m = HashMap<String, String>()
    override fun get(key: String): String? = m[key]
    override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}
