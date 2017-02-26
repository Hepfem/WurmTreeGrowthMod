/**
 * Created by Erica on 2017-02-22.
 */

package se.hepfem.wurm.wurmtreegrowthmod;

import java.util.logging.Level;
import java.util.logging.Logger;

import javassist.*;
import javassist.bytecode.*;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;

import java.util.Properties;

public class WurmTreeGrowthMod implements WurmServerMod, Configurable, PreInitable {

    private boolean activateMod = true;
    private double treeAgingChanceMultiplier = 1.0d;
    private int treeDeathOdds = 15;

    private final String checkForTreeGrowthMethodName = "checkForTreeGrowth";
    private final String checkForTreeGrowthMethodDesc = "(IIIBB)V";

    private Logger logger = Logger.getLogger(this.getClass().getName());

    @Override
    public void configure(Properties properties) {

        activateMod = Boolean.valueOf(properties.getProperty("activateMod", Boolean.toString(activateMod)));
        treeAgingChanceMultiplier = Double.valueOf(properties.getProperty("treeAgingChanceMultiplier", Double.toString(treeAgingChanceMultiplier)));
        treeDeathOdds = Integer.valueOf(properties.getProperty("treeDeathOdds", Integer.toString(treeDeathOdds)));
    }

    @Override
    public void preInit() {
        if (activateMod == true) {
            modifyTreeAgingChance();
        }
    }

    /**
     * This method injects byte code into the checkForTreeGrowth method of the TilePoller class in order to
     * modify the chance for a tree to age upon each polling.
     */
    private void modifyTreeAgingChance() {
        try {
            ClassPool cp = HookManager.getInstance().getClassPool();
            CtClass tilePollerClass = cp.get("com.wurmonline.server.zones.TilePoller");

            MethodInfo mi = tilePollerClass.getMethod(checkForTreeGrowthMethodName, checkForTreeGrowthMethodDesc).getMethodInfo();
            CodeAttribute ca = mi.getCodeAttribute();

            //We probably don't want to deal with negative values...
            double agingMultiplier = Math.abs(treeAgingChanceMultiplier);

            int deathOdds = treeDeathOdds;

            //Other values may produce unexpected results and overflows
            if (deathOdds < 0 || deathOdds > 127) {
                throw new NumberFormatException("deathOdds must be between 0-127 but was " + deathOdds);
            }

            //Invert aging multiplier (lower code value of chance = higher actual chance)
            if (agingMultiplier == 0) {
                agingMultiplier = 225.0d;
            } else if (agingMultiplier >= 225) {
                agingMultiplier = 0;
            } else {
                agingMultiplier = 1/agingMultiplier;
            }


            ConstPool constPool= ca.getConstPool();
            int ref = constPool.addDoubleInfo(agingMultiplier);

            CodeIterator codeIterator = ca.iterator();

            logger.log(Level.INFO, "Aging chance will be multiplied by " + agingMultiplier);
            //Try to find a reasonable place to inject aging multiplier from
            while (codeIterator.hasNext()) {

                int pos = codeIterator.next();
                int op = codeIterator.byteAt(pos);

                //There is only one use of SIPUSH before where we want to inject the code in this method
                if (op == CodeIterator.SIPUSH) {

                    //Insert gap before the call and add instructions:
                    codeIterator.insertGap(pos + 8, 10);
                    //Multiply chance with treeAgingMultiplier
                    codeIterator.writeByte(Bytecode.ILOAD, pos + 8);
                    codeIterator.writeByte(8, pos + 9);
                    codeIterator.writeByte(Bytecode.I2D, pos + 10);
                    codeIterator.writeByte(Bytecode.LDC2_W, pos + 11);
                    codeIterator.write16bit(ref, pos + 12);
                    codeIterator.writeByte(Bytecode.DMUL, pos + 14);
                    codeIterator.writeByte(Bytecode.D2I, pos + 15);
                    codeIterator.writeByte(Bytecode.ISTORE, pos + 16);
                    codeIterator.writeByte(8, pos + 17);
                    logger.log(Level.INFO, "Multiplier code injected");
                    break;
                }
            }

            //Find where the deathOdds need to be injected
            while (codeIterator.hasNext()) {

                int pos = codeIterator.next();
                int op = codeIterator.byteAt(pos);
                int nextOp = codeIterator.byteAt(pos+3);

                //This is a good "landmark"
                if (op == CodeIterator.IF_ICMPNE && nextOp == CodeIterator.ILOAD_1) {

                    // If odds = 1, set chance to 1
                    if (deathOdds == 1) {
                        //Insert gap before the call and add instructions:
                        codeIterator.insertGap(pos + 21, 4);
                        codeIterator.writeByte(Bytecode.BIPUSH, pos + 21);
                        codeIterator.writeByte(1, pos + 22);
                        codeIterator.writeByte(Bytecode.ISTORE, pos + 23);
                        codeIterator.writeByte(8, pos + 24);
                        logger.log(Level.INFO, "Odds code injected (odds 1/1)");
                        break;
                    } else {
                        //death odds = 0 infinitely small chance of tree dieing, approximate to 0
                        //rand.nextInt must have an upper bound of 1 to always produce a death chance of 0
                        if (deathOdds == 0) {
                            deathOdds = 1;
                        }
                        //Overwrite the upper bound of the rand.nextInt function with new odds
                        codeIterator.writeByte(deathOdds, pos + 15);
                        logger.log(Level.INFO, "Odds code injected");
                        break;
                    }

                }
            }

            mi.rebuildStackMap(cp);

        } catch (NotFoundException e) {
            throw new HookException(e);
        } catch (BadBytecode e) {
            e.printStackTrace();
        }

    }
}
