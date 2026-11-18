package com.xgimiru.player

/**
 * Обложка строки «Продолжить просмотр» для лаунчера. Lumen Home выбирает по настоящему размеру картинок
 * (правило «Витрины»): сначала смотрит обложку (poster), потом вторую картинку (thumbnail). Поэтому порядок:
 * фон сериала или фильма из TMDB, затем кадр серии из TMDB, иначе кадр из самого видео (640x360, Home его
 * не растягивает, а размывает в фон). Две колонки провайдера: третья картинка не нужна, если есть две из TMDB.
 * Старые лаунчеры (Google TV, Lumen Home 2.0) берут poster и получают тот же фон вместо кадра из видео.
 */
object ContinueArt {
    const val TMDB_IMG = "https://image.tmdb.org/t/p/"

    class Art(val poster: String?, val thumbnail: String?)

    fun pick(backdrop: String?, still: String?, grab: String?): Art {
        val l = listOf(backdrop, still, grab).filter { !it.isNullOrBlank() }.map { it!! }.distinct()
        return Art(l.getOrNull(0), l.getOrNull(1))
    }

    /** Картинка TMDB в w1280 по пути из API ("/abc.jpg"). */
    fun tmdb(path: String) = TMDB_IMG + "w1280" + path
}
