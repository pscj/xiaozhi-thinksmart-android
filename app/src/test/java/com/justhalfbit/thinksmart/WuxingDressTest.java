package com.justhalfbit.thinksmart;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.time.LocalDate;
import org.junit.Test;

/**
 * Host-side tests for the on-device 明日五行穿衣 computation. The ganzhi anchors are known
 * days: 1949-10-01 and 2024-01-01 are both 甲子日， 2000-01-01 is 戊午日， and 2026-11-13/14
 * are 辛卯/壬辰 (the two days whose published 五行穿衣 results the tier rules were checked
 * against — 卯/木日： 大吉火・次吉木・忌用土； 辰/土日： 大吉金・次吉土・平平木・慎用火・忌用水）.
 */
public class WuxingDressTest {

  @Test
  public void ganzhiAnchors() {
    assertEquals(27, WuxingDress.ganzhiIndex(LocalDate.of(2026, 11, 13)));
    assertEquals("辛卯", WuxingDress.ganzhiName(LocalDate.of(2026, 11, 13)));
    assertEquals(28, WuxingDress.ganzhiIndex(LocalDate.of(2026, 11, 14)));
    assertEquals("壬辰", WuxingDress.ganzhiName(LocalDate.of(2026, 11, 14)));
    assertEquals("戊午", WuxingDress.ganzhiName(LocalDate.of(2000, 1, 1)));
    assertEquals(0, WuxingDress.ganzhiIndex(LocalDate.of(1949, 10, 1)));
    assertEquals("甲子", WuxingDress.ganzhiName(LocalDate.of(1949, 10, 1)));
    assertEquals("甲子", WuxingDress.ganzhiName(LocalDate.of(2024, 1, 1)));
    // Consecutive days advance the cycle by exactly one, across the wrap.
    assertEquals(
        (WuxingDress.ganzhiIndex(LocalDate.of(2026, 11, 13)) + 1) % 60,
        WuxingDress.ganzhiIndex(LocalDate.of(2026, 11, 14)));
  }

  @Test
  public void dayElementFromBranch() {
    assertEquals(
        WuxingDress.Element.WOOD, WuxingDress.dayElement(LocalDate.of(2026, 11, 13))); // 卯
    assertEquals(
        WuxingDress.Element.EARTH, WuxingDress.dayElement(LocalDate.of(2026, 11, 14))); // 辰
    assertEquals(
        WuxingDress.Element.FIRE, WuxingDress.dayElement(LocalDate.of(2026, 11, 16))); // 午
    assertEquals(
        WuxingDress.Element.METAL, WuxingDress.dayElement(LocalDate.of(2026, 11, 18))); // 申
    assertEquals(
        WuxingDress.Element.WATER, WuxingDress.dayElement(LocalDate.of(2026, 11, 22))); // 子
  }

  @Test
  public void tiersForAnchorDates() {
    // 辰（土）日 — matches the published 2026-11-14 result exactly.
    assertArrayEquals(
        new WuxingDress.Element[] {
          WuxingDress.Element.METAL,
          WuxingDress.Element.EARTH,
          WuxingDress.Element.WOOD,
          WuxingDress.Element.FIRE,
          WuxingDress.Element.WATER
        },
        WuxingDress.tiersFor(LocalDate.of(2026, 11, 14)));
    // 卯（木）日 — 大吉火・次吉木・忌用土 per the published 2026-11-13 result;
    // 平平金（金克木）・慎用水（水生木）complete the five.
    assertArrayEquals(
        new WuxingDress.Element[] {
          WuxingDress.Element.FIRE,
          WuxingDress.Element.WOOD,
          WuxingDress.Element.METAL,
          WuxingDress.Element.WATER,
          WuxingDress.Element.EARTH
        },
        WuxingDress.tiersFor(LocalDate.of(2026, 11, 13)));
  }

  @Test
  public void tiersForEveryElement() {
    // Generating order 木→火→土→金→水→木； 大吉 = generated, 次吉 = same, 平平 = overcomes
    // the day, 慎用 = generates the day, 忌用 = overcome by the day.
    assertArrayEquals(
        new WuxingDress.Element[] {
          WuxingDress.Element.FIRE,
          WuxingDress.Element.WOOD,
          WuxingDress.Element.METAL,
          WuxingDress.Element.WATER,
          WuxingDress.Element.EARTH
        },
        WuxingDress.tiersForElement(WuxingDress.Element.WOOD));
    assertArrayEquals(
        new WuxingDress.Element[] {
          WuxingDress.Element.EARTH,
          WuxingDress.Element.FIRE,
          WuxingDress.Element.WATER,
          WuxingDress.Element.WOOD,
          WuxingDress.Element.METAL
        },
        WuxingDress.tiersForElement(WuxingDress.Element.FIRE));
    assertArrayEquals(
        new WuxingDress.Element[] {
          WuxingDress.Element.METAL,
          WuxingDress.Element.EARTH,
          WuxingDress.Element.WOOD,
          WuxingDress.Element.FIRE,
          WuxingDress.Element.WATER
        },
        WuxingDress.tiersForElement(WuxingDress.Element.EARTH));
    assertArrayEquals(
        new WuxingDress.Element[] {
          WuxingDress.Element.WATER,
          WuxingDress.Element.METAL,
          WuxingDress.Element.FIRE,
          WuxingDress.Element.EARTH,
          WuxingDress.Element.WOOD
        },
        WuxingDress.tiersForElement(WuxingDress.Element.METAL));
    assertArrayEquals(
        new WuxingDress.Element[] {
          WuxingDress.Element.WOOD,
          WuxingDress.Element.WATER,
          WuxingDress.Element.EARTH,
          WuxingDress.Element.METAL,
          WuxingDress.Element.FIRE
        },
        WuxingDress.tiersForElement(WuxingDress.Element.WATER));
  }

  @Test
  public void swatchesMatchTheSpec() {
    WuxingDress.Swatch[] metal = WuxingDress.swatches(WuxingDress.Element.METAL);
    assertEquals(3, metal.length);
    assertEquals("白", metal[0].name);
    assertEquals(0xfff5f5f5, metal[0].color);
    assertEquals("银", metal[1].name);
    assertEquals(0xffc0c6ce, metal[1].color);
    assertEquals("灰", metal[2].name);
    assertEquals(0xff8a8f98, metal[2].color);

    WuxingDress.Swatch[] earth = WuxingDress.swatches(WuxingDress.Element.EARTH);
    assertEquals(3, earth.length);
    assertEquals("黄", earth[0].name);
    assertEquals(0xffe8c33a, earth[0].color);
    assertEquals("咖", earth[1].name);
    assertEquals(0xffa97c50, earth[1].color);
    assertEquals("棕", earth[2].name);
    assertEquals(0xff7a4a2b, earth[2].color);

    WuxingDress.Swatch[] wood = WuxingDress.swatches(WuxingDress.Element.WOOD);
    assertEquals(2, wood.length);
    assertEquals("绿", wood[0].name);
    assertEquals(0xff4caf6d, wood[0].color);
    assertEquals("青", wood[1].name);
    assertEquals(0xff3aa6a0, wood[1].color);

    WuxingDress.Swatch[] fire = WuxingDress.swatches(WuxingDress.Element.FIRE);
    assertEquals(3, fire.length);
    assertEquals("红", fire[0].name);
    assertEquals(0xffd84a4a, fire[0].color);
    assertEquals("粉", fire[1].name);
    assertEquals(0xffef9ac0, fire[1].color);
    assertEquals("紫", fire[2].name);
    assertEquals(0xff8e6cc8, fire[2].color);

    WuxingDress.Swatch[] water = WuxingDress.swatches(WuxingDress.Element.WATER);
    assertEquals(2, water.length);
    assertEquals("黑", water[0].name);
    assertEquals(0xff26262b, water[0].color);
    assertEquals("蓝", water[1].name);
    assertEquals(0xff3a6fd8, water[1].color);
  }

  @Test
  public void tierNamesInDisplayOrder() {
    assertArrayEquals(
        new String[] {"大吉", "次吉", "平平", "慎用", "忌用"}, WuxingDress.TIER_NAMES);
  }
}
