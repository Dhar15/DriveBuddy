package app.drivebuddy.features.learning

import android.content.Context

/** Today's word and GK fact, kept on the phone so a second request the same day needs no network. */
class LearningCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("learning_cache", Context.MODE_PRIVATE)

    fun word(day: String): WordOfDay? {
        if (prefs.getString("word_day", null) != day) return null
        val word = prefs.getString("word_word", null) ?: return null
        val meaning = prefs.getString("word_meaning", null) ?: return null
        return WordOfDay(word, meaning, prefs.getString("word_example", "").orEmpty())
    }

    fun saveWord(day: String, w: WordOfDay) {
        prefs.edit().putString("word_day", day).putString("word_word", w.word)
            .putString("word_meaning", w.meaning).putString("word_example", w.example).apply()
    }

    fun gk(day: String): GkFact? {
        if (prefs.getString("gk_day", null) != day) return null
        val fact = prefs.getString("gk_fact", null) ?: return null
        return GkFact(prefs.getString("gk_category", "").orEmpty(), fact)
    }

    fun saveGk(day: String, f: GkFact) {
        prefs.edit().putString("gk_day", day).putString("gk_category", f.category)
            .putString("gk_fact", f.fact).apply()
    }
}