package com.betterstreamflix.app.familyfriday

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Genre
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.utils.TmdbUtils
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyFridayShelfTest {

    private val utc = ZoneOffset.UTC
    /** Friday 2026-10-09 12:00 UTC */
    private val fridayMs = 1_791_547_200_000L
    /** Saturday 2026-10-10 12:00 UTC */
    private val saturdayMs = 1_791_633_600_000L

    private val familyGenre = Genre("10751", "Family")

    @Test
    fun isFamilyFriday_trueOnFriday() {
        assertTrue(FamilyFridayShelf.isFamilyFriday(fridayMs, utc))
    }

    @Test
    fun isFamilyFriday_falseOnSaturday() {
        assertFalse(FamilyFridayShelf.isFamilyFriday(saturdayMs, utc))
    }

    @Test
    fun build_nullWhenNotKids() = runBlocking {
        val shelf = FamilyFridayShelf.build(
            catalogCategories = sampleCatalog(),
            nowMs = fridayMs,
            zone = utc,
            isKidsActive = { false },
            profileId = { "kids01" },
            filterAgeSafe = { it },
        )
        assertNull(shelf)
    }

    @Test
    fun build_nullWhenNotFriday() = runBlocking {
        val shelf = FamilyFridayShelf.build(
            catalogCategories = sampleCatalog(),
            nowMs = saturdayMs,
            zone = utc,
            isKidsActive = { true },
            profileId = { "kids01" },
            filterAgeSafe = { it },
        )
        assertNull(shelf)
    }

    @Test
    fun build_returnsExactlyThreeOnKidsFriday() = runBlocking {
        val shelf = FamilyFridayShelf.build(
            catalogCategories = sampleCatalog(),
            nowMs = fridayMs,
            zone = utc,
            isKidsActive = { true },
            profileId = { "kids01" },
            filterAgeSafe = { it },
        )
        assertNotNull(shelf)
        assertEquals(Category.FAMILY_FRIDAY, shelf!!.name)
        assertEquals(3, shelf.list.size)
    }

    @Test
    fun build_nullWhenFewerThanThreeAgeSafe() = runBlocking {
        val shelf = FamilyFridayShelf.build(
            catalogCategories = listOf(
                Category(
                    name = "Featured",
                    list = listOf(
                        kidsMovie("1", "A"),
                        kidsMovie("2", "B"),
                    ),
                ),
            ),
            nowMs = fridayMs,
            zone = utc,
            isKidsActive = { true },
            profileId = { "kids01" },
            filterAgeSafe = { it },
        )
        assertNull(shelf)
    }

    @Test
    fun build_respectsAgeFilterCeiling() = runBlocking {
        val catalog = listOf(
            Category(
                name = "Featured",
                list = listOf(
                    kidsMovie("1", "G Film", rating = "G"),
                    kidsMovie("2", "Y7 Film", rating = "TV-Y7"),
                    kidsMovie("3", "PG Film", rating = "PG"),
                    kidsMovie("4", "Teen Film", rating = "PG-13"),
                    kidsMovie("5", "R Film", rating = "R"),
                ),
            ),
        )
        val maxAge = 6
        val shelf = FamilyFridayShelf.build(
            catalogCategories = catalog,
            nowMs = fridayMs,
            zone = utc,
            isKidsActive = { true },
            profileId = { "kids01" },
            filterAgeSafe = { items ->
                items.filter { item ->
                    val movie = item as? Movie ?: return@filter false
                    val age = TmdbUtils.parseContentRatingToAge(movie.contentRating)
                    age != null && age <= maxAge
                }
            },
        )
        // Only G (0) survives maxAge 6; TV-Y7 is 7 → omit shelf.
        assertNull(shelf)
    }

    @Test
    fun build_deterministicForSameProfileAndFriday() = runBlocking {
        val catalog = sampleCatalog(size = 8)
        suspend fun pick(): List<String> =
            FamilyFridayShelf.build(
                catalogCategories = catalog,
                nowMs = fridayMs,
                zone = utc,
                isKidsActive = { true },
                profileId = { "kids01" },
                filterAgeSafe = { it },
            )!!.list.map { (it as Movie).id }
        assertEquals(pick(), pick())
    }

    @Test
    fun build_prefersKidsTiltWhenAvailable() = runBlocking {
        val catalog = listOf(
            Category(
                name = "Featured",
                list = listOf(
                    kidsMovie("k1", "Family One"),
                    kidsMovie("k2", "Family Two"),
                    kidsMovie("k3", "Family Three"),
                    Movie(id = "a1", title = "Action One", genres = listOf(Genre("28", "Action")))
                        .apply { contentRating = "PG" },
                    Movie(id = "a2", title = "Action Two", genres = listOf(Genre("28", "Action")))
                        .apply { contentRating = "PG" },
                    Movie(id = "a3", title = "Action Three", genres = listOf(Genre("28", "Action")))
                        .apply { contentRating = "PG" },
                ),
            ),
        )
        val shelf = FamilyFridayShelf.build(
            catalogCategories = catalog,
            nowMs = fridayMs,
            zone = utc,
            isKidsActive = { true },
            profileId = { "kids01" },
            filterAgeSafe = { it },
        )
        assertNotNull(shelf)
        assertTrue(shelf!!.list.all { (it as Movie).id.startsWith("k") })
    }

    @Test
    fun build_nullWhenKidsTiltEmptyEvenIfAgeSafeFull() = runBlocking {
        val catalog = listOf(
            Category(
                name = "Featured",
                list = listOf(
                    Movie(id = "a1", title = "Action One", genres = listOf(Genre("28", "Action")))
                        .apply { contentRating = "PG" },
                    Movie(id = "a2", title = "Action Two", genres = listOf(Genre("28", "Action")))
                        .apply { contentRating = "PG" },
                    Movie(id = "a3", title = "Action Three", genres = listOf(Genre("28", "Action")))
                        .apply { contentRating = "PG" },
                ),
            ),
        )
        val shelf = FamilyFridayShelf.build(
            catalogCategories = catalog,
            nowMs = fridayMs,
            zone = utc,
            isKidsActive = { true },
            profileId = { "kids01" },
            filterAgeSafe = { it },
        )
        assertNull(shelf)
    }

    private fun sampleCatalog(size: Int = 5): List<Category> =
        listOf(
            Category(
                name = "Featured",
                list = (1..size).map { kidsMovie("$it", "Title $it") },
            ),
        )

    private fun kidsMovie(
        id: String,
        title: String,
        rating: String = "PG",
    ): AppAdapter.Item =
        Movie(
            id = id,
            title = title,
            genres = listOf(familyGenre),
        ).apply { contentRating = rating }
}
