package ua.zaika.carremote.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.math.abs

class AirForceParserTest {

    companion object {
        lateinit var gaz: Gazetteer
        lateinit var fixture: List<AirForceParser.Post>

        @BeforeClass @JvmStatic fun load() {
            gaz = Gazetteer.parse(File("src/main/assets/geo/places.tsv").readText())
            fixture = readFixture(File("src/test/resources/kpszsu-posts.txt").readText())
        }

        /** «=== kpszsu/123 2026-11-01T06:17:41Z» opens a post; the lines under it are its text. */
        fun readFixture(s: String): List<AirForceParser.Post> {
            val out = ArrayList<AirForceParser.Post>()
            var head: List<String>? = null
            val body = StringBuilder()
            fun flush() {
                head?.let { out += AirForceParser.Post(it[1], Instant.parse(it[2]).toEpochMilli(), body.toString().trim()) }
                body.setLength(0)
            }
            for (line in s.lines()) {
                if (line.startsWith("=== ")) { flush(); head = line.split(' ') }
                else if (head != null) body.append(line).append('\n')
            }
            flush()
            return out
        }
    }

    private fun one(text: String, id: String = "kpszsu/1"): Sighting {
        val s = AirForceParser.parse(listOf(AirForceParser.Post(id, 0L, text)), gaz)
        assertEquals("one sighting for «$text»: $s", 1, s.size)
        return s[0]
    }

    private fun place(name: String) = gaz.choose(gaz.lookup(name.split(' ')), emptyList())!!
    private fun km(s: Sighting, p: Place) = AirForceParser.distKm(s.lat, s.lon, p.lat, p.lon)
    private fun km(s: Sighting, lat: Double, lon: Double) = AirForceParser.distKm(s.lat, s.lon, lat, lon)
    private fun angle(a: Int, b: Int): Int { val d = abs(a - b) % 360; return if (d > 180) 360 - d else d }
    private fun oblastOf(s: Sighting) = gaz.nearest(s.lat, s.lon)!!.oblast

    @Test fun gazetteerFindsCaseForms() {
        val vin = gaz.choose(gaz.lookup(listOf("Вінницю")), emptyList())!!
        assertEquals("Вінниця", vin.name)
        assertEquals("Канів", gaz.choose(gaz.lookup(listOf("Каневі")), emptyList())!!.name)
        assertEquals("Черкаси", gaz.choose(gaz.lookup(listOf("Черкас")), emptyList())!!.name)
        assertEquals("Кривий Ріг", gaz.choose(gaz.lookup(listOf("Кривого", "Рогу")), emptyList())!!.name)
        assertEquals("Біла Церква", gaz.choose(gaz.lookup(listOf("Білої", "Церкви")), emptyList())!!.name)
        assertEquals("вінницька", gaz.choose(gaz.lookup(listOf("Липовець")), listOf("вінницька"))!!.oblast)
        assertEquals("Київ", gaz.choose(gaz.lookup(listOf("Києва")), emptyList())!!.name)
    }

    @Test fun jetToVinnytsiaFromTheEast() {
        val s = one("🏍 Реактивний БпЛА на Вінницю зі сходу.")
        assertEquals("uav", s.kind); assertTrue(s.jet)
        assertTrue("~25 km from Vinnytsia: $s", km(s, 49.233, 28.468) in 15.0..35.0)
        assertTrue("east of it: $s", s.lon > 28.6)
        assertTrue(!s.targetOnly)
        assertNotNull(s.headingDeg); assertTrue("west: $s", angle(s.headingDeg!!, 270) <= 30)
    }

    @Test fun pastHaivoronTowardTeplyk() {
        val s = one("🏍 Реактивний БпЛА повз Гайворон на Кіровоградщині курсом на Теплик на Вінниччині.")
        assertTrue(s.jet)
        assertTrue("near Haivoron: $s", km(s, 48.34, 29.86) < 10)
        val toTeplyk = AirForceParser.bearing(s.lat, s.lon, 48.664, 29.747)
        assertTrue("toward Teplyk: $s", angle(s.headingDeg!!, toTeplyk) <= 15)
        assertTrue(AirForceParser.distKm(s.destLat!!, s.destLon!!, 48.664, 29.747) < 3)
    }

    @Test fun northOfCherkasyTowardKaniv() {
        val s = one("🏍 Реактивний БпЛА на півночі від Черкас, курс на Канів.")
        assertTrue("near Cherkasy: $s", km(s, 49.445, 32.059) < 25)
        assertTrue("north of it: $s", s.lat > 49.445)
        assertTrue("toward Kaniv (north-west): $s", angle(s.headingDeg!!, 315) <= 40)
    }

    @Test fun guidedBombsOnZaporizhzhiaOblast() {
        val s = one("🚀КАБи на Запорізьку область.")
        assertEquals("kab", s.kind)
        assertEquals("запорізька", oblastOf(s))
        assertTrue("only the target oblast is named: $s", s.targetOnly)
        assertTrue(s.uncertaintyKm >= 40)
    }

    @Test fun oblastSideIsPlacedTowardThatSide() {
        val s = one("🏍 Реактивний БпЛА на півночі Житомирщини, курс на захід.")
        assertEquals("житомирська", oblastOf(s))
        val r = gaz.regions["житомирська"]!!
        assertTrue(s.lat > r.lat)
        assertEquals(270, s.headingDeg)
        assertTrue(s.uncertaintyKm in 40.0..60.0)
    }

    @Test fun headerListLinesInheritTypeAndOblast() {
        val text = "🏍 Ситуація по реактивних БпЛА на Харківщині:\n- в центрі, курс на північ;\n- на заході, курс на Полтавщину;\n- на півдні, курс на захід."
        val s = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/2", 0L, text)), gaz)
        assertEquals(3, s.size)
        assertTrue(s.all { it.kind == "uav" && it.jet && oblastOf(it) == "харківська" })
        assertEquals(listOf("kpszsu/2:1", "kpszsu/2:2", "kpszsu/2:3"), s.map { it.id })
        assertEquals(0, s[0].headingDeg)
    }

    @Test fun multiLinePostGivesOneSightingPerLine() {
        val text = "🏍 Реактивний БпЛА на Дніпро зі сходу.\n🏍 Реактивний БпЛА на півночі від Кривого Рогу, курс на захід.\n" +
            "🏍 Реактивний БпЛА на півдні Кіровоградщини, курс на Миколаївщину.\n🏍 Реактивний БпЛА в центрі Харківщини, курс на Харків.\n" +
            "🏍 Реактивний БпЛА повз Суми на заході, курс на південь."
        val s = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/92136", 0L, text)), gaz)
        assertEquals(5, s.size)
        assertTrue(km(s[0], 48.46, 35.04) < 35 && s[0].lon > 35.04) // «на Дніпро зі сходу»: east of it
        assertTrue(km(s[1], 47.91, 33.39) < 25)
        assertEquals("кіровоградська", oblastOf(s[2]))
        assertEquals("харківська", oblastOf(s[3]))
        assertTrue(km(s[4], 50.91, 34.80) < 20 && s[4].lon < 34.80)
        assertEquals(180, s[4].headingDeg)
    }

    @Test fun seaWithoutLandIsDropped() {
        assertTrue(AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/3", 0L, "🛵 БпЛА з акваторії Чорного моря.")), gaz).isEmpty())
    }

    @Test fun seaOriginWithLandTargetIsTheTarget() {
        for ((text, town) in listOf(
            "🏍 Реактивний БпЛА у напрямку Одеси з акваторії Чорного моря" to "Одеса",
            "🏍 Реактивний БпЛА з акваторії Чорного моря на Одесу." to "Одеса",
            "🛵 Група ударних БпЛА з моря курсом на Вилкове" to "Вилкове",
        )) {
            val s = one(text)
            assertTrue("target-only: $s", s.targetOnly)
            assertTrue("at $town: $s", km(s, place(town)) < 2)
        }
    }

    @Test fun oblastOriginSitsOnTheSideFacingTheDestination() {
        val s = one("🛵 БпЛА з Хмельниччини на Рівненщину.")
        assertEquals("хмельницька", oblastOf(s))
        assertTrue("north of the oblast's middle: $s", s.lat > 49.8)
        assertTrue(angle(s.headingDeg!!, 340) <= 40)
    }

    @Test fun seaOriginOntoAnOblastComesAshoreInTheSouth() {
        for ((text, ob) in listOf("🛵 БпЛА з моря на Одещину." to "одеська", "🛵 БпЛА з моря курсом на Миколаївщину" to "миколаївська")) {
            val s = one(text)
            assertEquals(ob, oblastOf(s))
            assertTrue("a position, not just a target: $s", !s.targetOnly)
            assertEquals(0, s.headingDeg)
        }
    }

    @Test fun placeQualifiedByOblastInParentheses() {
        val s = one("🛵 Ударні БпЛА з Київщини (Тетіїв) ➡️ на Вінниччину.")
        assertEquals("uav", s.kind); assertTrue(!s.jet)
        assertTrue(km(s, 49.37, 29.67) < 10)
        assertTrue(angle(s.headingDeg!!, 250) <= 50)
    }

    @Test fun betweenTwoCities() {
        val s = one("🏍 Реактивні БпЛА між Полтавою та Кременчуком, курс на захід.")
        assertEquals("полтавська", oblastOf(s))
        assertEquals(270, s.headingDeg)
    }

    @Test fun cityHeaderOverTheCity() {
        val s = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/4", 0L, "⚠Дніпро\n🏍 Реактивний БпЛА над містом!")), gaz)
        assertEquals(1, s.size)
        assertTrue(km(s[0], 48.46, 35.04) < 10)
        assertNull(s[0].headingDeg)
    }

    @Test fun followUpPostContinuesTheThreat() {
        val posts = listOf(
            AirForceParser.Post("kpszsu/10", 0L, "🏍 Реактивний БпЛА на півночі від Черкас, курс на захід."),
            AirForceParser.Post("kpszsu/11", 5 * 60_000L, "Курс на Канів."),
        )
        val s = AirForceParser.parse(posts, gaz)
        assertEquals(2, s.size)
        assertTrue(s[1].jet)
        assertTrue(km(s[1], s[0].lat, s[0].lon) < 1)
        assertNotNull(s[1].destLat)
    }

    // ---- destination-only lines ----

    @Test fun destinationOnlyLinesAreTargetOnly() {
        for ((text, town) in listOf(
            "🛵 БпЛА курсом на Білу Церкву." to "Біла Церква",
            "🏍 Реактивний БпЛА курсом на Татарбунари на Одещині" to "Татарбунари",
            "🛵 БпЛА в напрямку н.п.Чугуїв на Харківщині" to "Чугуїв",
            "🏍 Реактивні БпЛА на Золотоношу на Черкащині" to "Золотоноша",
            "🏍 Тернопільщина: реактивний БпЛА курсом на Теребовлю." to "Теребовля",
            // The side and oblast after the target say where the target is.
            "🏍 Реактивний БпЛА курсом на Козятин на півночі Вінниччини." to "Козятин",
        )) {
            val s = one(text)
            val p = place(town)
            assertTrue("target-only: $s", s.targetOnly)
            assertTrue("at the target: $s", km(s, p) < 2)
            assertTrue(AirForceParser.distKm(s.destLat!!, s.destLon!!, p.lat, p.lon) < 2)
            assertNull("no heading: $s", s.headingDeg)
            assertTrue("the target is precise: $s", s.uncertaintyKm <= 10)
        }
    }

    @Test fun severalTargetsGiveOneTargetEach() {
        val two = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/20", 0L, "🏍 Реактивні БпЛА на Дніпро та Білу Церкву.")), gaz)
        assertEquals(2, two.size)
        assertTrue(two.all { it.targetOnly && it.headingDeg == null })
        assertTrue(km(two[0], place("Дніпро")) < 2 && km(two[1], place("Біла Церква")) < 2)
        val alt = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/21", 0L, "🛵 Київщина: ударний БпЛА курсом на Бровари/Київ")), gaz)
        assertTrue(alt.isNotEmpty() && alt.all { it.targetOnly })
        assertTrue(km(alt[0], place("Бровари")) < 2)
    }

    @Test fun currentPlaceBeforeCourseIsAPosition() {
        // «КАБи на Донеччину, курс на схід Дніпропетровщини»: over Donetsk oblast now.
        val s = one("🚀КАБи на Донеччину, курс на схід Дніпропетровщини.")
        assertTrue(!s.targetOnly)
        assertEquals("донецька", oblastOf(s))
        assertNotNull(s.headingDeg)
        // «на Миколаївщині у напрямку Баштанки зі сходу»: east of Bashtanka, flying west into it.
        val b = one("🏍 Реактивний БпЛА на Миколаївщині у напрямку Баштанки зі сходу")
        val bash = place("Баштанка")
        assertTrue(!b.targetOnly)
        assertTrue("east of Bashtanka: $b", b.lon > bash.lon && km(b, bash) in 15.0..35.0)
        assertTrue("west into it: $b", angle(b.headingDeg!!, 270) <= 30)
    }

    // ---- where it comes from ----

    @Test fun sideWordPlacesItOutOnThatSide() {
        val both = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/30", 0L,
            "🚀 Баражуючі боєприпаси \"Бандероль\" в напрямку н.п. Сенельникове та Дніпра зі сходу.")), gaz)
        assertEquals(2, both.size) // one toward each town
        val s = both[0]
        val syn = place("Синельникове")
        assertTrue(!s.targetOnly)
        assertTrue("east of Synelnykove: $s", s.lon > syn.lon && km(s, syn) in 15.0..35.0)
        assertTrue(angle(s.headingDeg!!, 270) <= 30)
        val n = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/31", 0L, "🏍 Реактивний БпЛА на Баришівку з півночі на Київщині.")), gaz).single()
        val bar = place("Баришівка")
        assertTrue("north of Baryshivka: $n", n.lat > bar.lat && km(n, bar) in 15.0..35.0)
        assertTrue(angle(n.headingDeg!!, 180) <= 30)
        val k = one("🏍 Реактивний БпЛА курсом на Київ з південно-західного напрямку.")
        assertTrue("south-west of Kyiv: $k", k.lat < 50.45 && k.lon < 30.52)
        assertTrue(angle(k.headingDeg!!, 45) <= 30)
    }

    @Test fun oblastAnchorWithTownHeadsToTheTown() {
        val s = one("🛵 Ударні БпЛА на півдні Житомирщини у напрямку Хмельниччини (Шепетівка)")
        assertEquals("житомирська", oblastOf(s))
        val shep = place("Шепетівка")
        assertTrue("toward Shepetivka: $s", angle(s.headingDeg!!, AirForceParser.bearing(s.lat, s.lon, shep.lat, shep.lon)) <= 5)
        val o = one("🛵 Ударний БпЛА на Вінниччину( на Оратів) з Черкащини.")
        val orativ = place("Оратів")
        assertTrue("toward Orativ: $o", angle(o.headingDeg!!, AirForceParser.bearing(o.lat, o.lon, orativ.lat, orativ.lon)) <= 5)
        assertTrue(AirForceParser.distKm(o.destLat!!, o.destLon!!, orativ.lat, orativ.lon) < 2)
    }

    // ---- follow-ups ----

    private fun kyivThread(vararg words: String): List<Sighting> {
        val posts = listOf(AirForceParser.Post("kpszsu/40", 0L, "🏍 Реактивний БпЛА курсом на Київ з південно-західного напрямку.")) +
            words.mapIndexed { i, w -> AirForceParser.Post("kpszsu/${41 + i}", (i + 1) * 60_000L, w) }
        return AirForceParser.parse(posts, gaz)
    }

    @Test fun oneWordFollowUpsResolveNearTheThread() {
        val s = kyivThread("Петропавлівська-Борщагівка.", "Борщагівка.", "Теремки.", "Гатне.")
        assertEquals("all four follow-ups placed: $s", 5, s.size)
        val kyiv = place("Київ")
        for (f in s.drop(1)) {
            assertTrue("in Kyiv: $f", km(f, kyiv) < 20)
            assertTrue(f.jet)
        }
        assertTrue(km(s[2], 50.43, 30.37) < 6)  // Борщагівка, the Kyiv one
        assertTrue(km(s[3], 50.37, 30.45) < 4)  // Теремки
        assertTrue(km(s[4], 50.36, 30.40) < 3)  // Гатне
        assertNotNull("moved from the previous point: ${s[3]}", s[3].headingDeg)
    }

    @Test fun followUpFarFromTheThreadIsDropped() {
        val posts = listOf(
            AirForceParser.Post("kpszsu/50", 0L, "🛵 Ударний БпЛА повз Ізмаїл, курс на північ."),
            AirForceParser.Post("kpszsu/51", 60_000L, "Борщагівка."),
            AirForceParser.Post("kpszsu/52", 2 * 60_000L, "Теремки."),
        )
        assertEquals(1, AirForceParser.parse(posts, gaz).size)
    }

    @Test fun hyphenAndSpaceAreTheSameName() {
        val a = gaz.choose(gaz.lookup(listOf("Петропавлівська-Борщагівка")), emptyList())
        val b = gaz.choose(gaz.lookup(listOf("Петропавлівська", "Борщагівка")), emptyList())
        assertNotNull(a); assertEquals(a, b)
        assertEquals("Івано-Франківськ", gaz.choose(gaz.lookup(listOf("Івано-Франківська")), emptyList())!!.name)
    }

    // ---- lists of places ----

    @Test fun listOfPlacesUsesTheLastOne() {
        val s = one("🏍 Реактивний БпЛА повз Немирів, Гнівань, Козятин.")
        val koz = place("Козятин"); val hn = place("Гнівань")
        assertTrue("at Koziatyn: $s", km(s, koz) < 10)
        assertTrue(angle(s.headingDeg!!, AirForceParser.bearing(hn.lat, hn.lon, koz.lat, koz.lon)) <= 5)
    }

    // ---- follow-ups continue the newest thread ----

    @Test fun followUpTakesTheNewestThreadNotTheNearest() {
        val posts = listOf(
            AirForceParser.Post("kpszsu/60", 0L, "🏍 Реактивні БпЛА курсом на Ніжин з півночі."),
            AirForceParser.Post("kpszsu/61", 3 * 60_000L, "🏍 Реактивний БпЛА на сході від Чернігова, курс на південь."),
            AirForceParser.Post("kpszsu/62", 5 * 60_000L, "Курс на Ніжин."),
        )
        val s = AirForceParser.parse(posts, gaz)
        assertEquals(3, s.size)
        assertTrue("from the Chernihiv thread: ${s[2]}", km(s[2], s[1].lat, s[1].lon) < 1)
        val nizhyn = place("Ніжин")
        assertTrue(angle(s[2].headingDeg!!, AirForceParser.bearing(s[1].lat, s[1].lon, nizhyn.lat, nizhyn.lon)) <= 3)
    }

    @Test fun followUpCourseOnZaporizhzhiaMeansTheCity() {
        val posts = listOf(
            AirForceParser.Post("kpszsu/70", 0L, "🚀КАБи на Запорізьку область."),
            AirForceParser.Post("kpszsu/71", 60_000L, "🏍 Реактивний БпЛА на Суми з півночі."),
            AirForceParser.Post("kpszsu/72", 2 * 60_000L, "Курс на Запоріжжя."),
        )
        val s = AirForceParser.parse(posts, gaz)
        assertEquals(3, s.size)
        assertTrue("the city, not a village: ${s[2]}", km(s[2], 47.851, 35.118) < 3)
        assertEquals("kab", s[2].kind)
    }

    @Test fun followUpMovesTheThreadSoTheNextOneContinuesFromThere() {
        val s = kyivThread("На Святопетрівське.", "Петропавлівська-Борщагівка.", "Борщагівка.", "Теремки.")
        assertEquals(5, s.size)
        val svp = place("Святопетрівське"); val pb = place("Петропавлівська Борщагівка")
        assertTrue(AirForceParser.distKm(s[1].destLat!!, s[1].destLon!!, svp.lat, svp.lon) < 2)
        assertTrue("from Sviatopetrivske: ${s[2]}", angle(s[2].headingDeg!!, AirForceParser.bearing(svp.lat, svp.lon, pb.lat, pb.lon)) <= 3)
        assertTrue("from Borshchahivka: ${s[4]}", angle(s[4].headingDeg!!, AirForceParser.bearing(s[3].lat, s[3].lon, s[4].lat, s[4].lon)) <= 3)
        assertTrue("south-east, toward Teremky: ${s[4]}", angle(s[4].headingDeg!!, 140) <= 30)
    }

    // ---- an oblast side and a list of towns ----

    @Test fun sideOfOblastWithTownsIsPlacedBeforeTheFirstTown() {
        for ((text, names) in listOf(
            "🛵Нові групи БпЛА на сході Дніпропетровщини - курсом на Петропавлівку, Юріївку." to listOf("Петропавлівка", "Юріївка"),
            "🛵 БпЛА на Львівщині, курс на Новий Яричів, Львів" to listOf("Новий Яричів", "Львів"),
        )) {
            val s = one(text)
            val t1 = gaz.choose(gaz.lookup(names[0].split(' ')), listOf(oblastOf(s)))!!
            val tn = gaz.choose(gaz.lookup(names[1].split(' ')), listOf(oblastOf(s)))!!
            assertTrue("about 25 km from ${t1.name}: $s", km(s, t1) in 15.0..35.0)
            assertTrue("before ${t1.name}, not beyond it: $s", km(s, tn) > AirForceParser.distKm(t1.lat, t1.lon, tn.lat, tn.lon))
            assertTrue("heading to ${t1.name}: $s", angle(s.headingDeg!!, AirForceParser.bearing(s.lat, s.lon, t1.lat, t1.lon)) <= 3)
            assertTrue(AirForceParser.distKm(s.destLat!!, s.destLon!!, t1.lat, t1.lon) < 2)
        }
    }

    @Test fun fromOneOblastToAnother() {
        val s = one("🛵 БпЛА з півночі Хмельниччини на Рівненщину, в напрямку обласного центру.")
        assertEquals("хмельницька", oblastOf(s))
        assertTrue("north of the oblast: $s", s.lat > gaz.regions["хмельницька"]!!.lat)
        val rivne = place("Рівне")
        assertTrue("toward Rivne: $s", angle(s.headingDeg!!, AirForceParser.bearing(s.lat, s.lon, rivne.lat, rivne.lon)) <= 3)
        assertTrue(AirForceParser.distKm(s.destLat!!, s.destLon!!, rivne.lat, rivne.lon) < 2)
    }

    @Test fun headerListSideIsThePositionAndCourseTheHeading() {
        val s = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/80", 0L,
            "🛵 БпЛА на Полтавщині:\n- на північно-західному, курс на захід.")), gaz).single()
        val r = gaz.regions["полтавська"]!!
        assertEquals("полтавська", oblastOf(s))
        assertTrue("north-west of the centre: $s", s.lat > r.lat && s.lon < r.lon)
        assertEquals(270, s.headingDeg)
    }

    @Test fun secondClauseIsASecondThreat() {
        val d = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/90", 0L,
            "🏍 Реактивний БпЛА на півдні від Дніпра, курс на захід та з північно-східного напрямку на обласний центр.")), gaz)
        assertEquals(2, d.size)
        val dnipro = place("Дніпро")
        assertTrue(d[0].lat < dnipro.lat); assertEquals(270, d[0].headingDeg)
        assertTrue("north-east of Dnipro: ${d[1]}", d[1].lat > dnipro.lat && d[1].lon > dnipro.lon && km(d[1], dnipro) in 15.0..35.0)
        assertTrue(angle(d[1].headingDeg!!, 225) <= 30)
        val k = AirForceParser.parse(listOf(AirForceParser.Post("kpszsu/91", 0L,
            "🏍 Реактивні БпЛА на півночі від Кривого Рогу, курс на захід та заході від міста, курс на південь.")), gaz)
        assertEquals(2, k.size)
        val kr = place("Кривий Ріг")
        assertTrue(k[0].lat > kr.lat); assertEquals(270, k[0].headingDeg)
        assertTrue("west of Kryvyi Rih: ${k[1]}", k[1].lon < kr.lon && km(k[1], kr) < 25)
        assertEquals(180, k[1].headingDeg)
    }

    @Test fun longListOfPlacesWidensTheUncertainty() {
        val s = one("🛵 Ударні БпЛА на Вінниччині повз Немирів, Гнівань, Козятин та Хмільник, курсом на захід.")
        val ps = listOf("Немирів", "Гнівань", "Козятин", "Хмільник").map { gaz.choose(gaz.lookup(listOf(it)), listOf("вінницька"))!! }
        val span = ps.maxOf { a -> ps.maxOf { b -> AirForceParser.distKm(a.lat, a.lon, b.lat, b.lon) } }
        assertTrue(span > 60)
        assertTrue("at Khmilnyk: $s", km(s, ps.last()) < 10)
        assertTrue("±${s.uncertaintyKm} vs span $span", s.uncertaintyKm >= span / 2)
        val short = one("🛵 Ударний БпЛА на Вінниччині, повз н.п.Турбів, Липовець, курсом на захід.")
        assertTrue(short.uncertaintyKm <= 10)
    }

    @Test fun wholeFixtureCoverage() {
        assertTrue("fixture has 100+ posts", fixture.size >= 100)
        val a = AirForceParser.analyze(fixture, gaz)
        val total = a.resolved.size + a.dropped.size
        println("AirForceParser coverage: ${fixture.size} posts, $total threat lines, ${a.resolved.size} placed, " +
            "${a.dropped.size} dropped, ${a.sightings.size} sightings, ${a.sightings.count { it.targetOnly }} target-only")
        println("Dropped lines (first 15):")
        a.dropped.forEach { println("  - $it") }
        println("Sample sightings:")
        a.sightings.take(400).forEach { println("  ${it.id} ${it.kind}${if (it.jet) "/jet" else ""}${if (it.targetOnly) " TARGET" else ""} %.3f,%.3f ±%.0f h=%s → %s | ${it.text}".format(java.util.Locale.ROOT, it.lat, it.lon, it.uncertaintyKm, it.headingDeg, it.placeName)) }
        assertTrue("most threat lines are placed", a.resolved.size >= total * 0.8)
        // Every placement is inside Ukraine's rough bounds.
        assertTrue(a.sightings.all { it.lat in 44.0..52.5 && it.lon in 22.0..40.5 })
    }
}
