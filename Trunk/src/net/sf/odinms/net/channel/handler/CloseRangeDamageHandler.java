/*
This file is part of the OdinMS Maple Story Server
Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc> 
Matthias Butz <matze@odinms.de>
Jan Christian Meyer <vimes@odinms.de>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License version 3
as published by the Free Software Foundation. You may not use, modify
or distribute this program under any other version of the
GNU Affero General Public License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.odinms.net.channel.handler;

import java.util.concurrent.ScheduledFuture;
import net.sf.odinms.client.Clones;
import net.sf.odinms.client.ISkill;
import net.sf.odinms.client.Enums.MapleBuffStat;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleCharacter.CancelCooldownAction;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.Enums.MapleStat;
import net.sf.odinms.client.SkillFactory;
import net.sf.odinms.server.constants.Skills;
import net.sf.odinms.net.MaplePacket;
import net.sf.odinms.server.MapleStatEffect;
import net.sf.odinms.server.TimerManager;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class CloseRangeDamageHandler extends AbstractDealDamageHandler {

    private boolean isFinisher(int skillId) {
        switch (skillId) {
            case Skills.Crusader.AxeComa:
            case Skills.Crusader.AxePanic:
            case Skills.Crusader.SwordComa:
            case Skills.Crusader.SwordPanic:
            case Skills.DawnWarrior3.Coma:
            case Skills.DawnWarrior3.Panic:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        AttackInfo attack = parseDamage(c.getPlayer(), slea, false);
        MapleCharacter player = c.getPlayer();
        if (player.isJounin()) {
            player.heal();
        }
        if (!c.getPlayer().canUseSkill(attack.skill)) {
            c.showMessage(5, "It appears you are unable to use this skill!");
            return;
        }
        MaplePacket packet = MaplePacketCreator.closeRangeAttack(player, attack);
        player.getMap().broadcastMessage(player, packet, false, true);
        for (Clones clone : c.getPlayer().getClones()) {
            player.getMap().broadcastMessage(clone.getClone(), MaplePacketCreator.closeRangeAttack(clone.getClone(), attack), false, true);
        }
        // handle combo orbconsume Energy charge
        int numFinisherOrbs = 0;
        Integer comboBuff = player.getBuffedValue(MapleBuffStat.COMBO);
        int energyChargeSkillLevel = player.getSkillLevel(player.getJobId() >= 1000 ? SkillFactory.getSkill(15100004) : SkillFactory.getSkill(5110001));

        if (isFinisher(attack.skill)) {
            if (comboBuff != null) {
                numFinisherOrbs = comboBuff.intValue() - 1;
            }
            player.handleOrbconsume();
        } else if (attack.numAttacked > 0 && comboBuff != null) {
            // handle combo orbgain
            if (attack.skill != 1111008) { // shout should not give orbs
                player.handleOrbGain();
            }
        }
        if (energyChargeSkillLevel > 0) {
            for (int i = 0; i < attack.numAttacked; i++) {
                player.handleEnergyChargeGain();
            }
        }

        // handle sacrifice hp loss
        if (attack.numAttacked > 0 && attack.skill == 1311005) {
            int totDamageToOneMonster = attack.allDamage.get(0).getRight().get(0).intValue(); // sacrifice attacks only 1 mob with 1 attack
            player.setHp(player.getHp() - totDamageToOneMonster * attack.getAttackEffect(player).getX() / 100);
            player.updateSingleStat(MapleStat.HP, player.getHp());
        }

        // handle charged blow
        if (attack.numAttacked > 0 && attack.skill == 1211002) {
            boolean advcharge_prob = false;
            int advcharge_level = player.getSkillLevel(SkillFactory.getSkill(1220010));
            if (advcharge_level > 0) {
                MapleStatEffect advcharge_effect = SkillFactory.getSkill(1220010).getEffect(advcharge_level);
                advcharge_prob = advcharge_effect.makeChanceResult();
            } else {
                advcharge_prob = false;
            }
            if (!advcharge_prob) {
                player.cancelEffectFromBuffStat(MapleBuffStat.WK_CHARGE);
            }
        }

        int maxdamage = c.getPlayer().getCurrentMaxBaseDamage();
        int attackCount = 1;
        if (attack.skill != 0) {
            MapleStatEffect effect = attack.getAttackEffect(c.getPlayer());
            attackCount = effect.getAttackCount();
            maxdamage *= effect.getDamage() / 100.0;
            maxdamage *= attackCount;
        }
        maxdamage = Math.min(maxdamage, 199999);
        if (attack.skill == 4211006) {
            maxdamage = 700000;
        } else if (numFinisherOrbs > 0) {
            maxdamage *= numFinisherOrbs;
        } else if (comboBuff != null) {
            ISkill combo = SkillFactory.getSkill(Skills.Crusader.ComboAttack);
            int comboLevel = player.getSkillLevel(combo);
            if (comboLevel == 0) {
                combo = SkillFactory.getSkill(Skills.DawnWarrior3.ComboAttack);
                comboLevel = player.getSkillLevel(combo);
            }
            MapleStatEffect comboEffect = combo.getEffect(comboLevel);
            double comboMod = 1.0 + (comboEffect.getDamage() / 100.0 - 1.0) * (comboBuff.intValue() - 1);
            maxdamage *= comboMod;
        }
        if (numFinisherOrbs == 0 && isFinisher(attack.skill)) {
            return; // can only happen when lagging o.o
        }
        if (isFinisher(attack.skill)) {
            maxdamage = 199999; // FIXME reenable damage calculation for finishers
        }
        if (attack.skill > 0) {
            ISkill skill = SkillFactory.getSkill(attack.skill);
            int skillLevel = c.getPlayer().getSkillLevel(skill);
            MapleStatEffect effect_ = skill.getEffect(skillLevel);
            if (skill.getId() == 1009 || skill.getId() == 10001009) {
                skillLevel = 1;
                c.getPlayer().setDojoEnergy(0);
                c.getSession().write(MaplePacketCreator.getEnergy(0));
            }
            if (effect_.getCooldown() > 0) {
                c.getSession().write(MaplePacketCreator.skillCooldown(attack.skill, effect_.getCooldown()));
                ScheduledFuture<?> timer = TimerManager.getInstance().schedule(new CancelCooldownAction(c.getPlayer(), attack.skill), effect_.getCooldown() * 1000);
                c.getPlayer().addCooldown(attack.skill, System.currentTimeMillis(), effect_.getCooldown() * 1000, timer);
            }
        }
        applyAttack(attack, player, maxdamage, attackCount);
    }
}
