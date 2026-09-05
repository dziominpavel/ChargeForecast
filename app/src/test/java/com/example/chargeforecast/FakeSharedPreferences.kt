package com.example.chargeforecast

import android.content.SharedPreferences

// Минимальная in-memory реализация SharedPreferences для JVM-тестов:
// интерфейс android.jar доступен на тестовом classpath, системных
// вызовов нет — реализация полностью наша.
class FakeSharedPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?
    ): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        // Слушатели кэшу сессии не нужны — заглушка.
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        // Слушатели кэшу сессии не нужны — заглушка.
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor {
            pending[key] = values?.toSet()
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            applyToValues()
            return true
        }

        override fun apply() {
            applyToValues()
        }

        private fun applyToValues() {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            pending.clear()
            removals.clear()
            clearAll = false
        }
    }
}
