package io.embodit.camcoverage.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTest {
    private fun quad(y: Float, cx: Float, cz: Float, w: Float, d: Float, semantic: String) =
        PlaneSurface("$semantic-$cx-$cz", semantic, listOf(Vec3(cx - w / 2, y, cz - d / 2), Vec3(cx + w / 2, y, cz - d / 2), Vec3(cx + w / 2, y, cz + d / 2), Vec3(cx - w / 2, y, cz + d / 2)))

    @Test
    fun depthCameraOnTheTableSeesTheWorkArea() {
        val floor = quad(0f, 0f, -2f, 6f, 6f, "FLOOR")
        val table = quad(0.75f, 0f, -2f, 1.2f, 0.8f, "TABLE")
        val room = RoomModel(listOf(floor, table))
        val zone = Zone(center = Vec3(0f, 0.75f, -2f), along = P2(1f, 0f))
        val hit = SurfaceHit(zone.world(zone.halfW + 0.25f, 0f, 0.75f), Vec3.UP, 1f, table)
        var cam = Placement.cameraFor(3, hit, room, Mode.WORKCELL, zone)!!.copy(type = CamType.DEPTH)
        cam = Aim.lookAt(cam, zone.boxCenter)
        println("cam pos=${cam.position} dir=${cam.dir}")
        val centre = zone.boxCenter
        println("depth at centre=${Frustum.depth(cam, Mode.WORKCELL, centre)} dist=${cam.position.distanceTo(centre)} blocks=${room.blocks(cam.position, centre)}")
        val r = Coverage.compute(Mode.WORKCELL, Layout(listOf(cam), zone), room, null, emptySet())
        println("cells=${r.zone.size} zero=${r.zeroCells} two+=${r.twoPlusShare} stat=${r.stats}")
        assertTrue("some cells seen", r.zeroCells < r.zone.size)
    }

    @Test
    fun standardCameraOnTheWallSeesTheWorkArea() {
        val floor = quad(0f, 0f, -2f, 6f, 6f, "FLOOR")
        val table = quad(0.75f, 0f, -2f, 1.2f, 0.8f, "TABLE")
        val wall = PlaneSurface("wall", "WALL", listOf(Vec3(-3f, 0f, -4f), Vec3(3f, 0f, -4f), Vec3(3f, 3f, -4f), Vec3(-3f, 3f, -4f)))
        val room = RoomModel(listOf(floor, table, wall))
        val zone = Zone(center = Vec3(0f, 0.75f, -2f), along = P2(1f, 0f))
        val hit = SurfaceHit(Vec3(0f, 2f, -4f), Vec3(0f, 0f, 1f), 1f, wall)
        var cam = Placement.cameraFor(1, hit, room, Mode.WORKCELL, zone)!!
        cam = Aim.lookAt(cam, zone.boxCenter)
        val r = Coverage.compute(Mode.WORKCELL, Layout(listOf(cam), zone), room, null, emptySet())
        println("wall cam pos=${cam.position} dir=${cam.dir} zero=${r.zeroCells}/${r.zone.size} stat=${r.stats}")
        assertTrue("wall camera sees cells", r.zeroCells < r.zone.size)
    }
}
