package com.justhalfbit.thinksmart;

import java.time.LocalDate;

/**
 * 明日五行穿衣 (tomorrow's five-element dressing guide), computed entirely on-device from
 * the date — no hub, no network. The day's stem-branch （干支） comes from the standard
 * day-count formula over the epoch day; the branch fixes the day's element, and the five
 * luck tiers follow the generating/overcoming cycles:
 *
 * <ul>
 *   <li>大吉 — the element the day generates （当日所生）
 *   <li>次吉 — the day's own element （同行）
 *   <li>平平 — the element that overcomes the day （克当日者）
 *   <li>慎用 — the element that generates the day （生当日者）
 *   <li>忌用 — the element the day overcomes （被当日所克）
 * </ul>
 *
 * <p>Generating cycle 木→火→土→金→水→木； overcoming 木克土、土克水、水克火、火克金、金克木. With
 * the elements indexed in generating order (WOOD 0, FIRE 1, EARTH 2, METAL 3, WATER 4),
 * "generates" is +1 and "overcomes" is +2 modulo 5, so every tier is a fixed offset.
 * The mapping was cross-checked against two independent public 五行穿衣 sources
 * (2026-11-13 卯/木日 and 2026-11-14 辰/土日 agree with the published results).
 */
final class WuxingDress {

  /** The five elements, indexed in generating order: each generates the next. */
  enum Element {
    WOOD("木"),
    FIRE("火"),
    EARTH("土"),
    METAL("金"),
    WATER("水");

    final String label;

    Element(String label) {
      this.label = label;
    }
  }

  /** One representative colour of an element's family: display name + ARGB swatch. */
  static final class Swatch {
    final String name;
    final int color;

    Swatch(String name, int color) {
      this.name = name;
      this.color = color;
    }
  }

  /** Tier names in display order, index-aligned with {@link #tiersForElement}. */
  static final String[] TIER_NAMES = {"大吉", "次吉", "平平", "慎用", "忌用"};

  private static final String[] STEMS = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
  private static final String[] BRANCHES = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};

  /** Branch (index-aligned with {@link #BRANCHES}) → element. */
  private static final Element[] BRANCH_ELEMENTS = {
    Element.WATER, // 子
    Element.EARTH, // 丑
    Element.WOOD, // 寅
    Element.WOOD, // 卯
    Element.EARTH, // 辰
    Element.FIRE, // 巳
    Element.FIRE, // 午
    Element.EARTH, // 未
    Element.METAL, // 申
    Element.METAL, // 酉
    Element.EARTH, // 戌
    Element.WATER // 亥
  };

  /**
   * Ganzhi index (0–59) of a date: {@code floorMod(epochDay + 17, 60)}. The +17 offset is
   * pinned by known days — 1949-10-01 and 2024-01-01 are both 甲子 (0), 2000-01-01 is 戊午
   * (54), 2026-11-13 is 辛卯 (27) — all four agree on the same offset.
   */
  static int ganzhiIndex(LocalDate date) {
    return (int) Math.floorMod(date.toEpochDay() + 17, 60);
  }

  /** The day's stem-branch name, e.g. 壬辰. */
  static String ganzhiName(LocalDate date) {
    int index = ganzhiIndex(date);
    return STEMS[index % 10] + BRANCHES[index % 12];
  }

  /** The day's element, from its earthly branch. */
  static Element dayElement(LocalDate date) {
    return BRANCH_ELEMENTS[ganzhiIndex(date) % 12];
  }

  /**
   * The five tiers' elements for a day element, in {@link #TIER_NAMES} order. Offsets in
   * generating-order indexing: generated-by-day +1, same 0, overcomes-day +3 (the element
   * X with X+2 ≡ day), generates-day +4, overcome-by-day +2.
   */
  static Element[] tiersForElement(Element day) {
    Element[] all = Element.values();
    int d = day.ordinal();
    return new Element[] {
      all[(d + 1) % 5], // 大吉: what the day generates
      day, // 次吉: same element
      all[(d + 3) % 5], // 平平: what overcomes the day
      all[(d + 4) % 5], // 慎用: what generates the day
      all[(d + 2) % 5] // 忌用: what the day overcomes
    };
  }

  /** The five tiers' elements for a date, in {@link #TIER_NAMES} order. */
  static Element[] tiersFor(LocalDate date) {
    return tiersForElement(dayElement(date));
  }

  /** Representative colours of an element's family, in display order. */
  static Swatch[] swatches(Element element) {
    switch (element) {
      case METAL:
        return new Swatch[] {
          new Swatch("白", 0xfff5f5f5), new Swatch("银", 0xffc0c6ce), new Swatch("灰", 0xff8a8f98)
        };
      case EARTH:
        return new Swatch[] {
          new Swatch("黄", 0xffe8c33a), new Swatch("咖", 0xffa97c50), new Swatch("棕", 0xff7a4a2b)
        };
      case WOOD:
        return new Swatch[] {new Swatch("绿", 0xff4caf6d), new Swatch("青", 0xff3aa6a0)};
      case FIRE:
        return new Swatch[] {
          new Swatch("红", 0xffd84a4a), new Swatch("粉", 0xffef9ac0), new Swatch("紫", 0xff8e6cc8)
        };
      case WATER:
      default:
        return new Swatch[] {new Swatch("黑", 0xff26262b), new Swatch("蓝", 0xff3a6fd8)};
    }
  }

  private WuxingDress() {}
}
