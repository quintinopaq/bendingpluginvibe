/*
 * Copyright 2020-2026 Moros
 *
 * This file is part of Bending.
 *
 * Bending is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Bending is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bending. If not, see <https://www.gnu.org/licenses/>.
 */

package me.moros.bending.paper.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import me.moros.bending.api.ability.AbilityDescription;
import me.moros.bending.api.ability.AbilityDescription.Sequence;
import me.moros.bending.api.ability.element.Element;
import me.moros.bending.api.locale.Translation;
import me.moros.bending.api.registry.Registries;
import me.moros.bending.api.user.User;
import me.moros.bending.api.util.TextUtil;
import me.moros.bending.common.locale.Message;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

/**
 * An interactive chest GUI allowing players to view and bind abilities to hotbar slots (1-9)
 * without needing chat commands.
 */
public final class AbilityBindGui implements InventoryHolder {
  private static final int INVENTORY_SIZE = 54;
  private static final int ABILITIES_PER_PAGE = 25;
  private static final int HOTBAR_START_SLOT = 45;

  private final Player player;
  private final User user;
  private final Locale locale;
  private final Inventory inventory;
  private final List<Element> elements;

  private @Nullable Element currentElement;
  private int currentPage;
  private @Nullable AbilityDescription selectedAbility;
  private List<AbilityDescription> cachedAbilities = List.of();
  private boolean viewingSequences = false;

  private AbilityBindGui(Player player, User user) {
    this.player = player;
    this.user = user;
    this.locale = player.getOrDefault(Identity.LOCALE, Translation.DEFAULT_LOCALE);
    this.elements = new ArrayList<>(user.elements());
    this.currentElement = elements.isEmpty() ? null : elements.get(0);
    this.currentPage = 0;
    this.selectedAbility = null;
    this.inventory = Bukkit.createInventory(this, INVENTORY_SIZE,
      plain(Component.text("✦ Bending: Alocar Habilidades ✦", NamedTextColor.DARK_AQUA, TextDecoration.BOLD)));
    updateCachedAbilities();
    render();
  }

  public static void open(Player player, User user) {
    if (user.elements().isEmpty()) {
      player.sendMessage(plain(Component.text("Você precisa escolher um elemento primeiro usando /b choose!", NamedTextColor.RED)));
      player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
      return;
    }
    AbilityBindGui gui = new AbilityBindGui(player, user);
    player.openInventory(gui.getInventory());
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }

  private void updateCachedAbilities() {
    if (viewingSequences) {
      cachedAbilities = Registries.SEQUENCES.stream()
        .filter(desc -> !desc.hidden())
        .filter(user::hasPermission)
        .sorted(Comparator.comparing(d -> d.key().value()))
        .map(seq -> (AbilityDescription) seq)
        .toList();
      return;
    }
    if (currentElement == null) {
      cachedAbilities = List.of();
      return;
    }
    cachedAbilities = Registries.ABILITIES.stream()
      .filter(desc -> !desc.hidden() && desc.canBind())
      .filter(user::hasPermission)
      .filter(desc -> desc.elements().contains(currentElement))
      .sorted(Comparator.comparing(d -> d.key().value()))
      .toList();
  }

  public void render() {
    inventory.clear();
    renderElementTabs();
    renderAbilitiesGrid();
    renderSeparatorRow();
    renderHotbarRow();
  }

  private void renderElementTabs() {
    ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, 1, plain(Component.text(" ")));
    for (int i = 0; i < 8; i++) {
      inventory.setItem(i, glass);
    }

    for (int i = 0; i < elements.size() && i < 7; i++) {
      Element elem = elements.get(i);
      boolean active = !viewingSequences && elem.equals(currentElement);
      Material mat = materialForElement(elem);
      Component name = active
        ? plain(Component.text("● ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(asRendered(elem.displayName()))
            .append(Component.text(" (Ativo)", NamedTextColor.YELLOW)))
        : plain(Component.text("○ ", NamedTextColor.GRAY)
            .append(asRendered(elem.displayName()))
            .append(Component.text(" (Clique para ver)", NamedTextColor.DARK_GRAY)));

      List<Component> lore = new ArrayList<>();
      lore.addAll(wrapToLore(elem.description(), NamedTextColor.GRAY));
      lore.add(Component.empty());
      lore.add(plain(Component.text(active ? "✔ Elemento visualizado" : "➜ Clique para listar habilidades",
        active ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));

      ItemStack tabItem = createItem(mat, 1, name, lore, active);
      inventory.setItem(i, tabItem);
    }

    // Sequence / Combos tab in slot 7
    Component comboTabName = viewingSequences
      ? plain(Component.text("✦ Sequências & Combos [ATIVO]", NamedTextColor.GOLD, TextDecoration.BOLD))
      : plain(Component.text("✧ Sequências & Combos", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
    List<Component> comboTabLore = List.of(
      plain(Component.text("Guia Informativo de Combos", NamedTextColor.YELLOW)),
      plain(Component.text("Acesse todas as sequências e", NamedTextColor.GRAY)),
      plain(Component.text("combinações elementais com", NamedTextColor.GRAY)),
      plain(Component.text("suas instruções e efeitos.", NamedTextColor.GRAY)),
      Component.empty(),
      plain(Component.text(viewingSequences ? "✔ Visualizando Combos" : "➜ Clique para abrir o Guia!",
        viewingSequences ? NamedTextColor.GREEN : NamedTextColor.YELLOW))
    );
    ItemStack comboTabItem = createItem(Material.NETHER_STAR, 1, comboTabName, comboTabLore, viewingSequences);
    inventory.setItem(7, comboTabItem);

    ItemStack closeItem = createItem(Material.BARRIER, 1,
      plain(Component.text("✖ Fechar Menu", NamedTextColor.RED, TextDecoration.BOLD)),
      List.of(plain(Component.text("Clique para fechar.", NamedTextColor.GRAY))), false);
    inventory.setItem(8, closeItem);
  }

  private void renderAbilitiesGrid() {
    if (cachedAbilities.isEmpty()) {
      ItemStack emptyItem = createItem(Material.STRUCTURE_VOID, 1,
        plain(Component.text("Nenhuma habilidade encontrada", NamedTextColor.RED)),
        List.of(plain(Component.text("Sem habilidades para este elemento.", NamedTextColor.GRAY))), false);
      inventory.setItem(22, emptyItem);
      return;
    }

    int total = cachedAbilities.size();
    int startIndex = currentPage * ABILITIES_PER_PAGE;
    int endIndex = Math.min(startIndex + ABILITIES_PER_PAGE, total);

    for (int i = startIndex; i < endIndex; i++) {
      AbilityDescription desc = cachedAbilities.get(i);
      int slot = 9 + (i - startIndex);
      boolean isSelected = desc.equals(selectedAbility);
      inventory.setItem(slot, buildAbilityItem(desc, isSelected));
    }

    if (currentPage > 0) {
      ItemStack prev = createItem(Material.ARROW, 1,
        plain(Component.text("◀ Anterior (" + currentPage + ")", NamedTextColor.YELLOW, TextDecoration.BOLD)),
        List.of(plain(Component.text("Clique para voltar.", NamedTextColor.GRAY))), false);
      inventory.setItem(34, prev);
    }
    if ((currentPage + 1) * ABILITIES_PER_PAGE < total) {
      ItemStack next = createItem(Material.ARROW, 1,
        plain(Component.text("Próxima (" + (currentPage + 2) + ") ▶", NamedTextColor.YELLOW, TextDecoration.BOLD)),
        List.of(plain(Component.text("Clique para avançar.", NamedTextColor.GRAY))), false);
      inventory.setItem(35, next);
    }
  }

  private ItemStack buildAbilityItem(AbilityDescription desc, boolean isSelected) {
    boolean isSequence = desc instanceof Sequence;
    Material mat = isSequence ? Material.NETHER_STAR : materialForAbility(desc);
    Component title = isSequence
      ? plain(asRendered(desc.displayName()).append(Component.text(" [COMBO]", NamedTextColor.GOLD, TextDecoration.BOLD)))
      : plain(asRendered(desc.displayName()));

    List<Component> lore = new ArrayList<>();
    if (isSequence) {
      lore.add(plain(Component.text("✦ SEQUÊNCIA / COMBO", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)));
      lore.add(plain(Component.text("➜ Clique para imprimir no chat!", NamedTextColor.YELLOW)));
      lore.add(Component.empty());
    } else if (isSelected) {
      lore.add(plain(Component.text("✔ SELECIONADA PARA ALOCAR", NamedTextColor.GREEN, TextDecoration.BOLD)));
      lore.add(plain(Component.text("➜ Clique num slot abaixo (1-9)!", NamedTextColor.YELLOW)));
      lore.add(Component.empty());
    } else {
      lore.add(plain(Component.text("➜ Clique esquerdo para selecionar", NamedTextColor.YELLOW)));
      lore.add(Component.empty());
    }

    lore.add(plain(Component.text("Elementos: ", NamedTextColor.GRAY)
      .append(asRendered(getElementDisplay(desc)))));

    boolean onCooldown = user.onCooldown(desc);
    lore.add(plain(Component.text("Status: ", NamedTextColor.GRAY)
      .append(onCooldown
        ? Component.text("⏳ Em Recarga", NamedTextColor.RED)
        : Component.text("✔ Pronta", NamedTextColor.GREEN))));

    lore.add(Component.empty());
    lore.add(plain(Component.text("Descrição: ", NamedTextColor.GOLD, TextDecoration.BOLD)));
    lore.addAll(wrapToLore(Message.ABILITY_DESCRIPTION.build(desc), NamedTextColor.GRAY));

    if (desc instanceof Sequence seq) {
      lore.add(Component.empty());
      lore.add(plain(Component.text("Ativação (Ordem do Combo):", NamedTextColor.GOLD, TextDecoration.BOLD)));
      lore.addAll(wrapToLore(seq.instructions(), NamedTextColor.YELLOW));
      lore.add(Component.empty());
      lore.add(plain(Component.text("ℹ Combos passivos executados", NamedTextColor.DARK_GRAY)));
      lore.add(plain(Component.text("  via golpes na hotbar.", NamedTextColor.DARK_GRAY)));
    } else {
      String key = desc.translationKey() + ".instructions";
      if (GlobalTranslator.translator().canTranslate(key, locale)) {
        lore.add(Component.empty());
        lore.add(plain(Component.text("Instruções de Uso:", NamedTextColor.AQUA, TextDecoration.BOLD)));
        lore.addAll(wrapToLore(Message.ABILITY_INSTRUCTIONS.build(desc), NamedTextColor.GRAY));
      }
    }

    return createItem(mat, 1, title, lore, isSelected || (isSequence && !onCooldown));
  }

  private void renderSeparatorRow() {
    ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, 1, plain(Component.text(" ")));
    for (int i = 36; i <= 44; i++) {
      inventory.setItem(i, glass);
    }

    ItemStack clearItem = createItem(Material.LAVA_BUCKET, 1,
      plain(Component.text("✖ Limpar Todos os Slots", NamedTextColor.RED, TextDecoration.BOLD)),
      List.of(
        plain(Component.text("Esvazia todos os 9 slots da hotbar.", NamedTextColor.GRAY)),
        plain(Component.text("➜ Clique para limpar tudo.", NamedTextColor.YELLOW))
      ), false);
    inventory.setItem(36, clearItem);

    ItemStack selectedIndicator;
    if (selectedAbility != null) {
      selectedIndicator = createItem(materialForAbility(selectedAbility), 1,
        plain(Component.text("Habilidade Selecionada:", NamedTextColor.GREEN, TextDecoration.BOLD)),
        List.of(
          plain(asRendered(selectedAbility.displayName())),
          Component.empty(),
          plain(Component.text("Clique num slot (1-9) para alocar.", NamedTextColor.YELLOW)),
          plain(Component.text("Ou clique nela acima para desmarcar.", NamedTextColor.GRAY))
        ), true);
    } else {
      selectedIndicator = createItem(Material.HOPPER, 1,
        plain(Component.text("Nenhuma Selecionada", NamedTextColor.YELLOW)),
        List.of(
          plain(Component.text("1. Clique numa habilidade acima.", NamedTextColor.GRAY)),
          plain(Component.text("2. Depois clique num slot (1-9).", NamedTextColor.GRAY))
        ), false);
    }
    inventory.setItem(40, selectedIndicator);

    ItemStack helpItem = createItem(Material.BOOK, 1,
      plain(Component.text("✦ Guia Rápido ✦", NamedTextColor.GOLD, TextDecoration.BOLD)),
      List.of(
        plain(Component.text("1. Clique numa habilidade acima.", NamedTextColor.WHITE)),
        plain(Component.text("2. Clique num slot (1-9) abaixo.", NamedTextColor.WHITE)),
        plain(Component.text("3. Clique direito para desvincular.", NamedTextColor.WHITE)),
        plain(Component.text("4. Feche o menu e use na hotbar!", NamedTextColor.GRAY))
      ), false);
    inventory.setItem(44, helpItem);
  }

  private void renderHotbarRow() {
    for (int i = 1; i <= 9; i++) {
      int invSlot = HOTBAR_START_SLOT + (i - 1);
      AbilityDescription bound = user.boundAbility(i);
      if (bound == null) {
        ItemStack emptySlot = createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, i,
          plain(Component.text("Slot " + i + ": ", NamedTextColor.GRAY)
            .append(Component.text("[Vazio]", NamedTextColor.DARK_GRAY))),
          List.of(
            plain(Component.text("Slot vazio.", NamedTextColor.GRAY)),
            plain(Component.text("➜ Selecione uma habilidade", NamedTextColor.YELLOW)),
            plain(Component.text("  e clique aqui para alocar.", NamedTextColor.YELLOW))
          ), false);
        inventory.setItem(invSlot, emptySlot);
      } else {
        boolean onCooldown = user.onCooldown(bound);
        Material mat = materialForAbility(bound);
        ItemStack boundItem = createItem(mat, i,
          plain(Component.text("Slot " + i + ": ", NamedTextColor.AQUA, TextDecoration.BOLD)
            .append(asRendered(bound.displayName()))),
          List.of(
            plain(Component.text("Elemento: ", NamedTextColor.GRAY).append(asRendered(getElementDisplay(bound)))),
            plain(Component.text("Status: ", NamedTextColor.GRAY)
              .append(onCooldown
                ? Component.text("⏳ Em Recarga", NamedTextColor.RED, TextDecoration.BOLD)
                : Component.text("✔ Pronta para uso", NamedTextColor.GREEN, TextDecoration.BOLD))),
            Component.empty(),
            plain(Component.text("➜ [Esq.] Vincular selecionada", NamedTextColor.YELLOW)),
            plain(Component.text("✖ [Dir.] Desvincular slot", NamedTextColor.RED))
          ), false);
        inventory.setItem(invSlot, boundItem);
      }
    }
  }

  public void handleClick(InventoryClickEvent event) {
    int rawSlot = event.getRawSlot();
    if (rawSlot < 0 || rawSlot >= INVENTORY_SIZE) {
      return;
    }

    // Close button
    if (rawSlot == 8) {
      player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
      player.closeInventory();
      return;
    }

    // Sequence / Combo tab (slot 7)
    if (rawSlot == 7) {
      if (!viewingSequences) {
        viewingSequences = true;
        currentPage = 0;
        selectedAbility = null;
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.4f);
        updateCachedAbilities();
        render();
      }
      return;
    }

    // Element tab selection
    if (rawSlot < elements.size() && rawSlot < 7) {
      Element clicked = elements.get(rawSlot);
      if (viewingSequences || !clicked.equals(currentElement)) {
        viewingSequences = false;
        currentElement = clicked;
        currentPage = 0;
        selectedAbility = null;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
        updateCachedAbilities();
        render();
      }
      return;
    }

    // Ability grid (slots 9 to 33)
    if (rawSlot >= 9 && rawSlot <= 33) {
      int abilityIndex = (currentPage * ABILITIES_PER_PAGE) + (rawSlot - 9);
      if (abilityIndex >= 0 && abilityIndex < cachedAbilities.size()) {
        AbilityDescription clickedDesc = cachedAbilities.get(abilityIndex);
        if (clickedDesc instanceof Sequence seq) {
          player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f);
          player.sendActionBar(plain(Component.text("✦ Combo: ", NamedTextColor.GOLD)
            .append(asRendered(seq.displayName()))
            .append(Component.text(" - Veja a ordem no chat!", NamedTextColor.YELLOW))));
          player.sendMessage(plain(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY)));
          player.sendMessage(plain(Component.text("✦ Sequência: ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(asRendered(seq.displayName()))));
          player.sendMessage(plain(Component.text("Ordem: ", NamedTextColor.YELLOW, TextDecoration.BOLD)
            .append(asRendered(seq.instructions()))));
          player.sendMessage(plain(Component.text("Descrição: ", NamedTextColor.GRAY)
            .append(asRendered(Message.ABILITY_DESCRIPTION.build(seq)))));
          player.sendMessage(plain(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY)));
          return;
        }

        if (clickedDesc.equals(selectedAbility)) {
          selectedAbility = null;
          player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
          player.sendActionBar(plain(Component.text("Seleção cancelada.", NamedTextColor.GRAY)));
        } else {
          selectedAbility = clickedDesc;
          player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.5f);
          player.sendActionBar(plain(Component.text("✔ Habilidade selecionada: ", NamedTextColor.GREEN)
            .append(asRendered(clickedDesc.displayName()))
            .append(Component.text(" » Clique em um slot (1-9) abaixo!", NamedTextColor.YELLOW))));
        }
        render();
      }
      return;
    }

    // Pagination buttons
    if (rawSlot == 34 && currentPage > 0) {
      currentPage--;
      player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
      render();
      return;
    }
    if (rawSlot == 35 && (currentPage + 1) * ABILITIES_PER_PAGE < cachedAbilities.size()) {
      currentPage++;
      player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
      render();
      return;
    }

    // Clear all slots
    if (rawSlot == 36) {
      for (int i = 1; i <= 9; i++) {
        user.bindAbility(i, null);
      }
      player.playSound(player.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1.0f, 1.0f);
      player.sendActionBar(plain(Component.text("✖ Todos os 9 slots foram esvaziados!", NamedTextColor.RED)));
      renderHotbarRow();
      return;
    }

    // Hotbar slots (45 to 53 -> slots 1 to 9)
    if (rawSlot >= HOTBAR_START_SLOT && rawSlot < HOTBAR_START_SLOT + 9) {
      int hotbarSlot = (rawSlot - HOTBAR_START_SLOT) + 1;

      if (event.isRightClick()) {
        if (user.boundAbility(hotbarSlot) != null) {
          user.bindAbility(hotbarSlot, null);
          player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1.0f, 1.0f);
          player.sendActionBar(plain(Component.text("✖ Slot " + hotbarSlot + " desvinculado!", NamedTextColor.RED)));
          renderHotbarRow();
        }
        return;
      }

      if (event.isLeftClick()) {
        if (selectedAbility != null) {
          user.bindAbility(hotbarSlot, selectedAbility);
          player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
          player.sendActionBar(plain(Component.text("✔ ", NamedTextColor.GREEN)
            .append(asRendered(selectedAbility.displayName()))
            .append(Component.text(" alocada no Slot " + hotbarSlot + "!", NamedTextColor.GREEN))));
          renderHotbarRow();
        } else {
          player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
          player.sendActionBar(plain(Component.text("⚠ Selecione uma habilidade acima primeiro!", NamedTextColor.RED)));
        }
      }
    }
  }

  private static final int MAX_LORE_WIDTH = 34;

  private List<Component> wrapToLore(Component component, NamedTextColor color) {
    Component rendered = asRendered(component);
    String text = PlainTextComponentSerializer.plainText().serialize(rendered);
    List<Component> lines = new ArrayList<>();
    for (String paragraph : text.split("\\r?\\n")) {
      if (paragraph.isBlank()) {
        continue;
      }
      for (String line : TextUtil.wrap(paragraph, MAX_LORE_WIDTH)) {
        lines.add(plain(Component.text(line, color)));
      }
    }
    return lines;
  }

  private Component asRendered(Component source) {
    return GlobalTranslator.render(source, locale);
  }

  private Component getElementDisplay(AbilityDescription desc) {
    if (desc.elements().isEmpty()) {
      return Component.text("Geral", NamedTextColor.GRAY);
    }
    if (desc.elements().size() > 1) {
      List<Component> list = new ArrayList<>();
      for (Element e : desc.elements()) {
        list.add(e.displayName());
      }
      return Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.text(" + ", NamedTextColor.DARK_GRAY)), list);
    }
    return desc.elements().iterator().next().displayName();
  }

  private Material materialForElement(Element element) {
    return switch (element) {
      case AIR -> Material.FEATHER;
      case WATER -> Material.PRISMARINE_SHARD;
      case EARTH -> Material.COBBLESTONE;
      case FIRE -> Material.FIRE_CHARGE;
    };
  }

  private Material materialForAbility(AbilityDescription desc) {
    if (desc.elements().size() > 1) {
      return Material.NETHER_STAR;
    }
    if (!desc.elements().isEmpty()) {
      return materialForElement(desc.elements().iterator().next());
    }
    return Material.NETHER_STAR;
  }

  private ItemStack createItem(Material mat, int amount, Component name) {
    return createItem(mat, amount, name, List.of(), false);
  }

  private ItemStack createItem(Material mat, int amount, Component name, List<Component> lore, boolean glow) {
    ItemStack stack = new ItemStack(mat, Math.max(1, Math.min(64, amount)));
    ItemMeta meta = stack.getItemMeta();
    if (meta != null) {
      meta.displayName(name);
      if (!lore.isEmpty()) {
        meta.lore(lore);
      }
      if (glow) {
        try {
          meta.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
          meta.addEnchant(Enchantment.UNBREAKING, 1, true);
          meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
      }
      meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
      stack.setItemMeta(meta);
    }
    return stack;
  }

  private static Component plain(Component component) {
    return component.decoration(TextDecoration.ITALIC, false);
  }
}

