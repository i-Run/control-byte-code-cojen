/*
 *  Copyright 2004 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.classfile;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The InstructionList class is used by the CodeBuilder to perform lower-level
 * bookkeeping operations and flow analysis.
 * 
 * @author Brian S O'Neill
 * @see CodeBuilder
 */
class InstructionList implements CodeBuffer {
    private static final boolean DEBUG = false;

    Instruction mFirst;
    Instruction mLast;

    boolean mResolved = false;

    private List<ExceptionHandler> mExceptionHandlers = new ArrayList<ExceptionHandler>(4);
    private List<LocalVariable> mLocalVariables = new ArrayList<LocalVariable>();
    private int mNextFixedVariableNumber;

    private int mMaxStack;
    private int mMaxLocals;

    private byte[] mByteCodes;
    private int mBufferLength;

    protected InstructionList() {
        super();
    }

    /**
     * Returns an immutable collection of all the instructions in this
     * InstructionList.
     */
    public Collection<Instruction> getInstructions() {
        return new AbstractCollection<Instruction>() {
            public Iterator<Instruction> iterator() {
                return new Iterator<Instruction>() {
                    private Instruction mNext = mFirst;

                    public boolean hasNext() {
                        return mNext != null;
                    }

                    public Instruction next() {
                        if (mNext == null) {
                            throw new NoSuchElementException();
                        }

                        Instruction current = mNext;
                        mNext = mNext.mNext;
                        return current;
                    }

                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }

            public int size() {
                int count = 0;
                for (Instruction i = mFirst; i != null; i = i.mNext) {
                    count++;
                }
                return count;
            }
        };
    }

    public int getMaxStackDepth() {
        resolve();
        return mMaxStack;
    }

    public int getMaxLocals() {
        resolve();
        return mMaxLocals;
    }

    public byte[] getByteCodes() {
        resolve();
        return mByteCodes;
    }

    public ExceptionHandler[] getExceptionHandlers() {
        resolve();

        ExceptionHandler[] handlers = new ExceptionHandler[mExceptionHandlers.size()];
        return mExceptionHandlers.toArray(handlers);
    }

    public void addExceptionHandler(ExceptionHandler handler) {
        mExceptionHandlers.add(handler);
    }

    public LocalVariable createLocalVariable(String name, TypeDesc type) {
        LocalVariable var = new LocalVariableImpl(mLocalVariables.size(), name, type, -1);
        mLocalVariables.add(var);
        return var;
    }

    /**
     * All parameters must be defined before adding instructions.
     */
    public LocalVariable createLocalParameter(String name, TypeDesc type) {
        LocalVariable var = new LocalVariableImpl
            (mLocalVariables.size(), name, type, mNextFixedVariableNumber);
        mLocalVariables.add(var);
        mNextFixedVariableNumber += type.isDoubleWord() ? 2 : 1;
        return var;
    }

    private void resolve() {
        if (mResolved) {
            return;
        }

        if (!DEBUG) {
            resolve0();
        } else {
            try {
                resolve0();
            } finally {
                System.out.println("-- Instructions --");

                for (Instruction i : getInstructions()) {
                    System.out.println(i);
                }
            }
        }
    }

    private void resolve0() {
        mMaxStack = 0;
        mMaxLocals = 0;

        Instruction instr;

        // Sweep through the instructions, preparing for flow analysis.
        int instrCount = 0;
        for (instr = mFirst; instr != null; instr = instr.mNext) {
            // Set address to instruction index.
            instr.reset(instrCount++);
        }

        // Make sure exception handlers are registered with all guarded
        // instructions.
        for (ExceptionHandler handler : mExceptionHandlers) {
            instr = (Instruction)handler.getStartLocation();
            Instruction end = (Instruction)handler.getEndLocation();
            for ( ; instr != null && instr != end; instr = instr.mNext) {
                instr.addExceptionHandler(handler);
            }
        }

        // Perform variable liveness flow analysis for each local variable, in
        // order to determine which register it should be assigned. Takes
        // advantage of the fact that instruction addresses are not yet
        // resolved to true addresses, but are instead indexes. This means the
        // liveness analysis operates on smaller BitLists, which makes some
        // operations (i.e. intersection) a bit faster.
        {
            int size = mLocalVariables.size();
            BitList[] liveIn = new BitList[size];
            BitList[] liveOut = new BitList[size];
            for (int v=0; v<size; v++) {
                liveIn[v] = new BitList(instrCount);
                liveOut[v] = new BitList(instrCount);
            }
            
            livenessAnalysis(liveIn, liveOut);
            
            // Register number -> list of variables that use that register.
            List<List<LocalVariable>> registerUsers = new ArrayList<List<LocalVariable>>();
            
            // First fill up list with variables that have a fixed number.
            for (int v=0; v<size; v++) {
                LocalVariableImpl var = (LocalVariableImpl)mLocalVariables.get(v);
                if (var.isFixedNumber()) {
                    addRegisterUser(registerUsers, var);
                    // Ensure that max locals is large enough to hold parameters.
                    int num = var.getNumber();
                    if (var.isDoubleWord()) {
                        num++;
                    }
                    if (num >= mMaxLocals) {
                        mMaxLocals = num + 1;
                    }
                }
            }
            
            // Merge bit lists together.
            BitList[] live = liveIn;
            for (int v=0; v<size; v++) {
                live[v].or(liveOut[v]);
                if (live[v].isAllClear()) {
                    // Variable isn't needed.
                    live[v] = null;
                }
            }
            
            for (int v=0; v<size; v++) {
                if (live[v] == null) {
                    continue;
                }
                LocalVariableImpl var = (LocalVariableImpl)mLocalVariables.get(v);
                if (var.isFixedNumber()) {
                    continue;
                }
                int r = 0;
                while (true) {
                    r = findAvailableRegister(registerUsers, r, live, v);
                    if (var.isDoubleWord()) {
                        if (findAvailableRegister(registerUsers, ++r, live, v) == r) {
                            // Found consecutive registers, required for double word.
                            r--;
                            break;
                        }
                    } else {
                        break;
                    }
                }
                var.setNumber(r);
                addRegisterUser(registerUsers, var);
            }
            
            mMaxLocals = Math.max(mMaxLocals, registerUsers.size());
        } // end liveness analysis

        // Perform stack flow analysis to determine the max stack size.
        {
            // Start the flow analysis at the first instruction.
            Map<Instruction, Integer> subAdjustMap = new HashMap<Instruction, Integer>(11);
            stackResolve(0, mFirst, subAdjustMap);
            
            // Continue flow analysis into exception handler entry points.
            for (ExceptionHandler handler : mExceptionHandlers) {
                Instruction enter = (Instruction)handler.getCatchLocation();
                stackResolve(1, enter, subAdjustMap);
            }
        }
            
        // Okay, build up the byte code and set real instruction locations.
        // Multiple passes may be required because instructions may adjust
        // their size as locations are set. Changing size affects the
        // locations of other instructions, so that is why additional passes
        // are required.
            
        boolean passAgain;
        do {
            passAgain = false;
            
            mByteCodes = new byte[instrCount * 2]; // estimate
            mBufferLength = 0;
            
            for (instr = mFirst; instr != null; instr = instr.mNext) {
                if (!instr.isResolved()) {
                    passAgain = true;
                }
                
                if (instr instanceof Label) {
                    if (instr.mLocation != mBufferLength) {
                        if (instr.mLocation >= 0) {
                            // If the location of this label is not where it
                            // should be, (most likely because an instruction
                            // needed to expand in size) then do another pass.
                            passAgain = true;
                        }
                        instr.mLocation = mBufferLength;
                    }
                } else {
                    instr.mLocation = mBufferLength;
                    
                    byte[] bytes = instr.getBytes();
                    if (bytes != null) {
                        if (passAgain) {
                            // If there is going to be another pass, don't 
                            // bother collecting bytes into the array. Just 
                            // expand the the length variable.
                            mBufferLength += bytes.length;
                        } else {
                            addBytes(bytes);
                        }
                    }
                }
            }
        } while (passAgain); // do {} while ();
        
        if (mBufferLength != mByteCodes.length) {
            byte[] newBytes = new byte[mBufferLength];
            System.arraycopy(mByteCodes, 0, newBytes, 0, mBufferLength);
            mByteCodes = newBytes;
        }

        // Set resolved at end because during resolution, this field gets
        // set false again while changes are being made to the list
        // of instructions.
        mResolved = true;
    }

    private void addBytes(byte[] code) {
        growBuffer(code.length);
        System.arraycopy(code, 0, mByteCodes, mBufferLength, code.length);
        mBufferLength += code.length;
    }

    private void growBuffer(int amount) {
        if ((mBufferLength + amount) > mByteCodes.length) {
            int newCapacity = mByteCodes.length * 2;
            if ((mBufferLength + amount) > newCapacity) {
                newCapacity = mBufferLength + amount;
            }

            byte[] newBuffer = new byte[newCapacity];
            System.arraycopy(mByteCodes, 0, newBuffer, 0, mBufferLength);
            mByteCodes = newBuffer;
        }
    }

    private void livenessAnalysis(BitList[] liveIn, BitList[] liveOut) {
        // Track stores to variables to see if the result is discarded.
        List<StoreLocalInstruction>[] localStores = new List[liveIn.length];

        int passCount = -1;
        boolean passAgain;
        do {
            passCount++;
            passAgain = false;

            for (Instruction instr = mLast; instr != null; instr = instr.mPrev) {
                int n = instr.getLocation();

                int useIndex = -1;
                int defIndex = -1;

                if (instr instanceof LocalOperandInstruction) {
                    LocalOperandInstruction loi = (LocalOperandInstruction)instr;
                    LocalVariableImpl var = loi.getLocalVariable();
                    int varIndex = var.getIndex();

                    if (loi.isLoad()) {
                        useIndex = varIndex;
                    }

                    if (loi.isStore()) {
                        defIndex = varIndex;
                        if (passCount == 0 && loi instanceof StoreLocalInstruction) {
                            List<StoreLocalInstruction> stores = localStores[varIndex];
                            if (stores == null) {
                                stores = new ArrayList<StoreLocalInstruction>();
                                localStores[varIndex] = stores;
                            }
                            stores.add((StoreLocalInstruction)loi);
                        }
                    }
                }

                for (int v=liveIn.length; --v>=0; ) {
                    boolean setLiveIn, setLiveOut;

                    if (useIndex == v || (v != defIndex && liveOut[v].get(n))) {
                        passAgain |= liveIn[v].set(n);
                        setLiveIn = true;
                    } else {
                        setLiveIn = false;
                    }

                    setLiveOut = false;

                    if (instr.isFlowThrough() && instr.mNext != null) {
                        if (liveIn[v].get(instr.mNext.getLocation())) {
                            setLiveOut = true;
                            passAgain |= liveOut[v].set(n);
                        }
                    }

                    Location[] targets = instr.getBranchTargets();
                    if (targets != null) {
                        for (int i=0; i<targets.length; i++) {
                            Instruction targetInstr = (Instruction)targets[i];
                            if (liveIn[v].get(targetInstr.getLocation())) {
                                setLiveOut = true;
                                passAgain |= liveOut[v].set(n);
                            }
                        }
                    }

                    Collection<ExceptionHandler> handlers = instr.getExceptionHandlers();
                    if (handlers != null) {
                        for (ExceptionHandler handler : handlers) {
                            Instruction catchInstr = (Instruction)handler.getCatchLocation();
                            if (liveIn[v].get(catchInstr.getLocation())) {
                                setLiveOut = true;
                                passAgain |= liveOut[v].set(n);
                            }
                        }
                    }

                    if (!setLiveIn && setLiveOut && v != defIndex) {
                        // Set liveIn entry now that liveOut has been
                        // updated. This greatly reduces the number of full
                        // passes required.
                        passAgain |= liveIn[v].set(n);
                    }
                }
            }
        } while (passAgain); // do {} while ();

        // See which local store instructions should discard their results.
        for (int v=localStores.length; --v>=0; ) {
            List<StoreLocalInstruction> stores = localStores[v];
            if (stores != null) {
                for (int i=stores.size(); --i>=0; ) {
                    StoreLocalInstruction instr = stores.get(i);
                    if (!liveOut[v].get(instr.getLocation())) {
                        instr.discardResult();
                    }
                }
            }
        }
    }

    private void addRegisterUser(List<List<LocalVariable>> registerUsers, LocalVariable var) {
        int num = var.getNumber();
        if (num < 0) {
            throw new IllegalStateException("Local variable number not resolved");
        }
        getRegisterUsers(registerUsers, num).add(var);
        if (var.isDoubleWord()) {
            getRegisterUsers(registerUsers, num + 1).add(var);
        }
    }

    private List<LocalVariable> getRegisterUsers(List<List<LocalVariable>> registerUsers,int num) {
        while (registerUsers.size() <= num) {
            registerUsers.add(new ArrayList<LocalVariable>());
        }
        return registerUsers.get(num);
    }

    /**
     * @param registerUsers
     * @param r index into registerUsers
     * @return index into registerUsers which is available, which may be equal
     * to r or equal to the size of registerUsers
     */
    private int findAvailableRegister(List<List<LocalVariable>> registerUsers,
                                      int r, BitList[] live, int v) {
        registerScan:
        for (; r<registerUsers.size(); r++) {
            List users = getRegisterUsers(registerUsers, r);
            for (int i=0; i<users.size(); i++) {
                int v2 = ((LocalVariableImpl)users.get(i)).getIndex();
                if (live[v].intersects(live[v2])) {
                    continue registerScan;
                }
            }
            break;
        }
        return r;
    }

    private int stackResolve(int stackDepth, 
                             Instruction instr, 
                             Map<Instruction, Integer> subAdjustMap) {
        while (instr != null) {
            // Set the stack depth, marking this instruction as being visited.
            // If already visited, break out of this flow.
            if (instr.mStackDepth < 0) {
                instr.mStackDepth = stackDepth;
            } else {
                if (instr.mStackDepth != stackDepth) {
                    throw new IllegalStateException
                        ("Stack depth different at previously visited " +
                         "instruction: " + instr.mStackDepth + 
                         " != " + stackDepth);
                }

                break;
            }

            // Determine the next instruction to flow down to.
            Instruction next = null;

            if (instr.isFlowThrough()) {
                if ((next = instr.mNext) == null) {
                    throw new IllegalStateException("Execution flows through end of method");
                }
            }

            stackDepth += instr.getStackAdjustment();
            if (stackDepth > mMaxStack) {
                mMaxStack = stackDepth;
            } else if (stackDepth < 0) {
                throw new IllegalStateException("Stack depth is negative: " + stackDepth);
            }

            Location[] targets = instr.getBranchTargets();

            if (targets != null) {
                for (int i=0; i<targets.length; i++) {
                    LabelInstruction targetInstr = (LabelInstruction)targets[i];

                    if (i == 0 && next == null) {
                        // Flow to the first target if instruction doesn't
                        // flow to its next instruction.
                        next = targetInstr;
                        continue;
                    }

                    if (!instr.isSubroutineCall()) {
                        stackResolve(stackDepth, targetInstr, subAdjustMap);
                    } else {
                        Integer subAdjust = subAdjustMap.get(targetInstr);

                        if (subAdjust == null) {
                            int newDepth = stackResolve(stackDepth, targetInstr, subAdjustMap);
                            subAdjust = new Integer(newDepth - stackDepth);
                            subAdjustMap.put(targetInstr, subAdjust);
                        }

                        stackDepth += subAdjust.intValue();
                    }
                }
            }

            instr = next;
        }

        return stackDepth;
    }

    private class LocalVariableImpl implements LocalVariable {
        private final int mIndex;

        private String mName;
        private TypeDesc mType;

        private int mNumber;
        private boolean mFixed;

        public LocalVariableImpl(int index, String name, TypeDesc type, int number) {
            mIndex = index;
            mName = name;
            mType = type;
            mNumber = number;
            if (number >= 0) {
                mFixed = true;
            }
        }

        int getIndex() {
            return mIndex;
        }

        /**
         * May return null if this LocalVariable is unnamed.
         */
        public String getName() {
            return mName;
        }

        public void setName(String name) {
            mName = name;
        }

        public TypeDesc getType() {
            return mType;
        }

        public boolean isDoubleWord() {
            return mType.isDoubleWord();
        }

        public int getNumber() {
            return mNumber;
        }

        public Set<LocationRange> getLocationRangeSet() {
            // TODO
            return null;
        }

        public void setNumber(int number) {
            mNumber = number;
        }

        public void setFixedNumber(int number) {
            mNumber = number;
            mFixed = true;
        }

        public boolean isFixedNumber() {
            return mFixed;
        }

        public String toString() {
            if (getName() != null) {
                return String.valueOf(getType()) + ' ' + getName();
            } else {
                return String.valueOf(getType());
            }
        }
    }

    /////////////////////////////////////////////////////////////////////////
    //
    // Begin inner class definitions for instructions of the InstructionList.
    //
    /////////////////////////////////////////////////////////////////////////

    /**
     * An Instruction is an element in an InstructionList, and represents a 
     * Java byte code instruction.
     */
    public abstract class Instruction implements Location {
        private int mStackAdjust;

        Instruction mPrev;
        Instruction mNext;

        // Indicates what the stack depth is when this instruction is reached.
        // Is -1 if not reached. Flow analysis sets this value.
        int mStackDepth = -1;

        // Indicates the address of this instruction is, or -1 if not known.
        int mLocation = -1;

        private Set<ExceptionHandler> mExceptionHandlers;

        /**
         * Newly created instructions are automatically added to the
         * InstructionList.
         */
        public Instruction(int stackAdjust) {
            mStackAdjust = stackAdjust;
            add();
        }

        /**
         * This constructor allows sub-classes to disable auto-adding to the
         * InstructionList.
         */
        protected Instruction(int stackAdjust, boolean addInstruction) {
            mStackAdjust = stackAdjust;

            if (addInstruction) {
                add();
            }
        }

        /**
         * Add this instruction to the end of the InstructionList. If the
         * Instruction is already in the list, then it is moved to the end.
         */
        protected void add() {
            InstructionList.this.mResolved = false;

            if (mPrev != null) {
                mPrev.mNext = mNext;
            }

            if (mNext != null) {
                mNext.mPrev = mPrev;
            }

            mNext = null;

            if (InstructionList.this.mFirst == null) {
                mPrev = null;
                InstructionList.this.mFirst = this;
            } else {
                mPrev = InstructionList.this.mLast;
                InstructionList.this.mLast.mNext = this;
            }

            InstructionList.this.mLast = this;
        }

        /**
         * Insert an Instruction immediately following this one.
         */
        public void insert(Instruction instr) {
            InstructionList.this.mResolved = false;

            instr.mPrev = this;
            instr.mNext = mNext;

            mNext = instr;

            if (this == InstructionList.this.mLast) {
                InstructionList.this.mLast = instr;
            }
        }

        /**
         * Removes this Instruction from its parent InstructionList.
         */
        public void remove() {
            InstructionList.this.mResolved = false;

            if (mPrev != null) {
                mPrev.mNext = mNext;
            }

            if (mNext != null) {
                mNext.mPrev = mPrev;
            }

            if (this == InstructionList.this.mFirst) {
                InstructionList.this.mFirst = mNext;
            }

            if (this == InstructionList.this.mLast) {
                InstructionList.this.mLast = mPrev;
            }

            mPrev = null;
            mNext = null;
        }

        /**
         * Replace this Instruction with another one.
         */
        public void replace(Instruction replacement) {
            if (replacement == null) {
                remove();
                return;
            }

            InstructionList.this.mResolved = false;

            replacement.mPrev = mPrev;
            replacement.mNext = mNext;

            if (mPrev != null) {
                mPrev.mNext = replacement;
            }

            if (mNext != null) {
                mNext.mPrev = replacement;
            }

            if (this == InstructionList.this.mFirst) {
                InstructionList.this.mFirst = replacement;
            }

            if (this == InstructionList.this.mLast) {
                InstructionList.this.mLast = replacement;
            }
        }

        /**
         * Returns a positive, negative or zero value indicating what affect
         * this generated instruction has on the runtime stack.
         */
        public int getStackAdjustment() {
            return mStackAdjust;
        }

        /**
         * Returns the stack depth for when this instruction is reached. If the
         * value is negative, then this instruction is never reached.
         */
        public int getStackDepth() {
            return mStackDepth;
        }

        /**
         * Returns the address of this instruction or -1 if not known.
         */
        public int getLocation() {
            return mLocation;
        }

        /**
         * Returns all of the targets that this instruction may branch to. Not
         * all instructions support branching, and null is returned by default.
         */
        public Location[] getBranchTargets() {
            return null;
        }

        /**
         * Returns an all the exception handlers that wraps this instruction,
         * or null if none.
         */
        public Collection<ExceptionHandler> getExceptionHandlers() {
            return mExceptionHandlers;
        }

        /**
         * Adds an exception handler that wraps this instruction.
         */
        public void addExceptionHandler(ExceptionHandler handler) {
            if (mExceptionHandlers == null) {
                mExceptionHandlers = new HashSet<ExceptionHandler>(4);
            }
            mExceptionHandlers.add(handler);
        }

        /**
         * Returns true if execution flow may continue after this instruction.
         * It may be a goto, a method return, an exception throw or a
         * subroutine return. Default implementation returns true.
         */
        public boolean isFlowThrough() {
            return true;
        }

        public boolean isSubroutineCall() {
            return false;
        }

        /**
         * Returns null if this is a pseudo instruction and no bytes are
         * generated.
         */
        public abstract byte[] getBytes();

        /**
         * An instruction is resolved when it has all information needed to
         * generate correct byte code.
         */
        public abstract boolean isResolved();

        public int compareTo(Location other) {
            if (this == other) {
                return 0;
            }

            int loca = getLocation();
            int locb = other.getLocation();

            if (loca < locb) {
                return -1;
            } else if (loca > locb) {
                return 1;
            } else {
                return 0;
            }
        }

        /**
         * Returns a string containing the type of this instruction, the stack
         * adjustment and the list of byte codes. Unvisted instructions are
         * marked with an asterisk.
         */
        public String toString() {
            String name = getClass().getName();
            int index = name.lastIndexOf('.');
            if (index >= 0) {
                name = name.substring(index + 1);
            }
            index = name.lastIndexOf('$');
            if (index >= 0) {
                name = name.substring(index + 1);
            }

            StringBuffer buf = new StringBuffer(name.length() + 20);

            int adjust = getStackAdjustment();
            int depth = getStackDepth();

            if (depth >= 0) {
                buf.append(' ');
            } else {
                buf.append('*');
            }

            buf.append('[');
            buf.append(mLocation);
            buf.append("] ");

            buf.append(name);
            buf.append(" (");

            if (depth >= 0) {
                buf.append(depth);
                buf.append(" + ");
                buf.append(adjust);
                buf.append(" = ");
                buf.append(depth + adjust);
            } else {
                buf.append(adjust);
            }

            buf.append(") ");

            try {
                byte[] bytes = getBytes();
                boolean wide = false;
                if (bytes != null) {
                    for (int i=0; i<bytes.length; i++) {
                        if (i > 0) {
                            buf.append(',');
                        }

                        byte code = bytes[i];

                        if (i == 0 || wide) {
                            buf.append(Opcode.getMnemonic(code));
                            wide = code == Opcode.WIDE;
                        } else {
                            buf.append(code & 0xff);
                        }
                    }
                }
            } catch (Exception e) {
            }

            return buf.toString();
        }

        /**
         * Reset this instruction in preparation for flow analysis.
         */
        void reset(int instrCount) {
            mStackDepth = -1;
            // Start with a fake location.
            mLocation = instrCount;
        }
    }

    /**
     * Defines a pseudo instruction for a label. No byte code is ever generated
     * from a label. Labels are not automatically added to the list.
     */
    public class LabelInstruction extends Instruction implements Label {
        public LabelInstruction() {
            super(0, false);
        }

        /**
         * Set this label's branch location to be the current address
         * in this label's parent CodeBuilder or InstructionList.
         *
         * @return This Label.
         */
        public Label setLocation() {
            add();
            return this;
        }

        /**
         * @return -1 when not resolved yet
         */ 
        public int getLocation() throws IllegalStateException {
            int loc; 
            if ((loc = mLocation) < 0) {
                if (mPrev == null && mNext == null) {
                    throw new IllegalStateException("Label location is not set");
                }
            }
            return loc;
        }

        /**
         * Always returns null.
         */
        public byte[] getBytes() {
            return null;
        }

        public boolean isResolved() {
            return getLocation() >= 0;
        }
    }

    /**
     * Defines a code instruction and has storage for byte codes.
     */
    public class CodeInstruction extends Instruction {
        protected byte[] mBytes;

        private Set mExceptionHandlers;

        public CodeInstruction(int stackAdjust) {
            super(stackAdjust);
        }

        protected CodeInstruction(int stackAdjust, boolean addInstruction) {
            super(stackAdjust, addInstruction);
        }

        public CodeInstruction(int stackAdjust, byte b) {
            super(stackAdjust);
            mBytes = new byte[] {b};
        }

        public CodeInstruction(int stackAdjust, byte[] bytes) {
            super(stackAdjust);
            mBytes = bytes;
        }

        public boolean isFlowThrough() {
            if (mBytes != null && mBytes.length > 0) {
                switch (mBytes[0]) {
                case Opcode.GOTO:
                case Opcode.GOTO_W:
                case Opcode.IRETURN:
                case Opcode.LRETURN:
                case Opcode.FRETURN:
                case Opcode.DRETURN:
                case Opcode.ARETURN:
                case Opcode.RETURN:
                case Opcode.ATHROW:
                    return false;
                }
            }

            return true;
        }

        public byte[] getBytes() {
            return mBytes;
        }

        public boolean isResolved() {
            return true;
        }
    }

    /**
     * Defines a branch instruction, like a goto, jsr or any conditional 
     * branch.
     */
    public class BranchInstruction extends CodeInstruction {
        private Location mTarget;
        private boolean mHasShortHop = false;
        private boolean mIsSub = false;

        public BranchInstruction(int stackAdjust, 
                                 byte opcode, Location target) {
            this(stackAdjust, true, opcode, target);
        }

        private BranchInstruction(int stackAdjust, boolean addInstruction,
                                  byte opcode, Location target) {
            super(stackAdjust, addInstruction);

            mTarget = target;

            switch (opcode) {
            case Opcode.JSR_W:
                mIsSub = true;
                // Flow through to next case.
            case Opcode.GOTO_W:
                mBytes = new byte[5];
                mBytes[0] = opcode;
                break;
            case Opcode.JSR:
                mIsSub = true;
                // Flow through to next case.
            case Opcode.GOTO:
            case Opcode.IF_ACMPEQ:
            case Opcode.IF_ACMPNE:
            case Opcode.IF_ICMPEQ:
            case Opcode.IF_ICMPNE:
            case Opcode.IF_ICMPLT:
            case Opcode.IF_ICMPGE:
            case Opcode.IF_ICMPGT:
            case Opcode.IF_ICMPLE:
            case Opcode.IFEQ:
            case Opcode.IFNE:
            case Opcode.IFLT:
            case Opcode.IFGE:
            case Opcode.IFGT:
            case Opcode.IFLE:
            case Opcode.IFNONNULL:
            case Opcode.IFNULL:
                mBytes = new byte[3];
                mBytes[0] = opcode;
                break;
            default:
                throw new IllegalArgumentException
                    ("Opcode not a branch instruction: " + 
                     Opcode.getMnemonic(opcode));
            }
        }

        public Location[] getBranchTargets() {
            return new Location[] {mTarget};
        }

        public boolean isSubroutineCall() {
            return mIsSub;
        }

        public byte[] getBytes() {
            if (!isResolved() || mHasShortHop) {
                return mBytes;
            }

            int offset = mTarget.getLocation() - mLocation;
            byte opcode = mBytes[0];

            if (opcode == Opcode.GOTO_W || opcode == Opcode.JSR_W) {
                mBytes[1] = (byte)(offset >> 24);
                mBytes[2] = (byte)(offset >> 16);
                mBytes[3] = (byte)(offset >> 8);
                mBytes[4] = (byte)(offset >> 0);
            } else if (-32768 <= offset && offset <= 32767) {
                mBytes[1] = (byte)(offset >> 8);
                mBytes[2] = (byte)(offset >> 0);
            } else if (opcode == Opcode.GOTO || opcode == Opcode.JSR) {
                mBytes = new byte[5];
                if (opcode == Opcode.GOTO) {
                    mBytes[0] = Opcode.GOTO_W;
                } else {
                    mBytes[0] = Opcode.JSR_W;
                }
                mBytes[1] = (byte)(offset >> 24);
                mBytes[2] = (byte)(offset >> 16);
                mBytes[3] = (byte)(offset >> 8);
                mBytes[4] = (byte)(offset >> 0);
            } else {
                // The if branch requires a 32 bit offset.

                // Convert:
                //
                //           if <cond> goto target
                //           // reached if <cond> false
                // target:   // reached if <cond> true

                // to this:
                //
                //           if not <cond> goto shortHop
                //           goto_w target
                // shortHop: // reached if <cond> false
                // target:   // reached if <cond> true

                mHasShortHop = true;

                opcode = Opcode.reverseIfOpcode(opcode);

                mBytes[0] = opcode;
                mBytes[1] = (byte)0;
                mBytes[2] = (byte)8;

                // insert goto_w instruction after this one.
                insert
                    (new BranchInstruction(0, false, Opcode.GOTO_W, mTarget));
            }

            return mBytes;
        }

        public boolean isResolved() {
            return mTarget.getLocation() >= 0;
        }
    }

    /**
     * Defines an instruction that has a single operand which references a
     * constant in the constant pool.
     */
    public class ConstantOperandInstruction extends CodeInstruction {
        private ConstantInfo mInfo;

        public ConstantOperandInstruction(int stackAdjust,
                                          byte[] bytes,
                                          ConstantInfo info) {
            super(stackAdjust, bytes);
            mInfo = info;
        }

        public byte[] getBytes() {
            int index = mInfo.getIndex();

            if (index < 0) {
                throw new IllegalStateException("Constant pool index not resolved");
            }

            mBytes[1] = (byte)(index >> 8);
            mBytes[2] = (byte)index;

            return mBytes;
        }

        public boolean isResolved() {
            return mInfo.getIndex() >= 0;
        }
    }

    /**
     * Defines an instruction that loads a constant onto the stack from the
     * constant pool.
     */
    public class LoadConstantInstruction extends CodeInstruction {
        private ConstantInfo mInfo;
        private boolean mWideOnly;

        public LoadConstantInstruction(int stackAdjust,
                                       ConstantInfo info) {
            this(stackAdjust, info, false);
        }

        public LoadConstantInstruction(int stackAdjust,
                                       ConstantInfo info,
                                       boolean wideOnly) {
            super(stackAdjust);
            mInfo = info;
            mWideOnly = wideOnly;
        }

        public boolean isFlowThrough() {
            return true;
        }

        public byte[] getBytes() {
            int index = mInfo.getIndex();

            if (index < 0) {
                throw new IllegalStateException("Constant pool index not resolved");
            }

            if (mWideOnly) {
                byte[] bytes = new byte[3];
                bytes[0] = Opcode.LDC2_W;
                bytes[1] = (byte)(index >> 8);
                bytes[2] = (byte)index;
                return bytes;
            } else if (index <= 255) {
                byte[] bytes = new byte[2];
                bytes[0] = Opcode.LDC;
                bytes[1] = (byte)index;
                return bytes;
            } else {
                byte[] bytes = new byte[3];
                bytes[0] = Opcode.LDC_W;
                bytes[1] = (byte)(index >> 8);
                bytes[2] = (byte)index;
                return bytes;
            }
        }

        public boolean isResolved() {
            return mInfo.getIndex() >= 0;
        }
    }

    /**
     * Defines an instruction that contains an operand for referencing a
     * LocalVariable.
     */
    public abstract class LocalOperandInstruction extends CodeInstruction {
        protected LocalVariableImpl mLocal;

        public LocalOperandInstruction(int stackAdjust, LocalVariable local) {
            super(stackAdjust);
            mLocal = (LocalVariableImpl)local;
        }

        public boolean isResolved() {
            return mLocal.getNumber() >= 0;
        }

        public LocalVariableImpl getLocalVariable() {
            return mLocal;
        }

        public int getVariableNumber() {
            int varNum = mLocal.getNumber();

            if (varNum < 0) {
                throw new IllegalStateException("Local variable number not resolved");
            }

            return varNum;
        }

        public abstract boolean isLoad();

        public abstract boolean isStore();
    }

    /**
     * Defines an instruction that loads a local variable onto the stack.
     */
    public class LoadLocalInstruction extends LocalOperandInstruction {
        public LoadLocalInstruction(int stackAdjust, LocalVariable local) {
            super(stackAdjust, local);
        }

        public boolean isFlowThrough() {
            return true;
        }

        public byte[] getBytes() {
            int varNum = getVariableNumber();
            byte opcode;
            boolean writeIndex = false;

            int typeCode = mLocal.getType().getTypeCode();

            switch(varNum) {
            case 0:
                switch (typeCode) {
                default:
                    opcode = Opcode.ALOAD_0;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LLOAD_0;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FLOAD_0;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DLOAD_0;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ILOAD_0;
                    break;
                }
                break;
            case 1:
                switch (typeCode) {
                default:
                    opcode = Opcode.ALOAD_1;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LLOAD_1;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FLOAD_1;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DLOAD_1;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ILOAD_1;
                    break;
                }
                break;
            case 2:
                switch (typeCode) {
                default:
                    opcode = Opcode.ALOAD_2;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LLOAD_2;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FLOAD_2;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DLOAD_2;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ILOAD_2;
                    break;
                }
                break;
            case 3:
                switch (typeCode) {
                default:
                    opcode = Opcode.ALOAD_3;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LLOAD_3;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FLOAD_3;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DLOAD_3;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ILOAD_3;
                    break;
                }
                break;
            default:
                writeIndex = true;

                switch (typeCode) {
                default:
                    opcode = Opcode.ALOAD;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LLOAD;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FLOAD;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DLOAD;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ILOAD;
                    break;
                }
                break;
            }

            if (!writeIndex) {
                mBytes = new byte[] { opcode };
            } else {
                if (varNum <= 255) {
                    mBytes = new byte[] { opcode, (byte)varNum };
                } else {
                    mBytes = new byte[] 
                    {
                        Opcode.WIDE,
                        opcode,
                        (byte)(varNum >> 8),
                        (byte)varNum
                    };
                }
            }

            return mBytes;
        }

        public boolean isLoad() {
            return true;
        }

        public boolean isStore() {
            return false;
        }
    }

    /**
     * Defines an instruction that stores a value from the stack into a local 
     * variable.
     */
    public class StoreLocalInstruction extends LocalOperandInstruction {
        private boolean mDiscardResult;

        public StoreLocalInstruction(int stackAdjust, LocalVariable local) {
            super(stackAdjust, local);
        }

        public boolean isFlowThrough() {
            return true;
        }

        public byte[] getBytes() {
            if (mDiscardResult) {
                // Liveness analysis discovered that the results of this store
                // are not needed so just pop if off the stack.
                return new byte[] { mLocal.isDoubleWord() ? Opcode.POP2 : Opcode.POP };
            }

            int varNum = getVariableNumber();

            byte opcode;
            boolean writeIndex = false;

            int typeCode = mLocal.getType().getTypeCode();

            switch(varNum) {
            case 0:
                switch (typeCode) {
                default:
                    opcode = Opcode.ASTORE_0;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LSTORE_0;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FSTORE_0;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DSTORE_0;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ISTORE_0;
                    break;
                }
                break;
            case 1:
                switch (typeCode) {
                default:
                    opcode = Opcode.ASTORE_1;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LSTORE_1;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FSTORE_1;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DSTORE_1;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ISTORE_1;
                    break;
                }
                break;
            case 2:
                switch (typeCode) {
                default:
                    opcode = Opcode.ASTORE_2;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LSTORE_2;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FSTORE_2;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DSTORE_2;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ISTORE_2;
                    break;
                }
                break;
            case 3:
                switch (typeCode) {
                default:
                    opcode = Opcode.ASTORE_3;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LSTORE_3;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FSTORE_3;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DSTORE_3;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ISTORE_3;
                    break;
                }
                break;
            default:
                writeIndex = true;

                switch (typeCode) {
                default:
                    opcode = Opcode.ASTORE;
                    break;
                case TypeDesc.LONG_CODE:
                    opcode = Opcode.LSTORE;
                    break;
                case TypeDesc.FLOAT_CODE:
                    opcode = Opcode.FSTORE;
                    break;
                case TypeDesc.DOUBLE_CODE:
                    opcode = Opcode.DSTORE;
                    break;
                case TypeDesc.INT_CODE:
                case TypeDesc.BOOLEAN_CODE:
                case TypeDesc.BYTE_CODE:
                case TypeDesc.CHAR_CODE:
                case TypeDesc.SHORT_CODE:
                    opcode = Opcode.ISTORE;
                    break;
                }
                break;
            }

            if (!writeIndex) {
                mBytes = new byte[] { opcode };
            } else {
                if (varNum <= 255) {
                    mBytes = new byte[] { opcode, (byte)varNum };
                } else {
                    mBytes = new byte[] 
                    {
                        Opcode.WIDE,
                        opcode,
                        (byte)(varNum >> 8),
                        (byte)varNum
                    };
                }
            }

            return mBytes;
        }

        public boolean isResolved() {
            return true;
        }

        public boolean isLoad() {
            return false;
        }

        public boolean isStore() {
            return true;
        }

        public void discardResult() {
            mDiscardResult = true;
        }
    }

    /**
     * Defines a ret instruction for returning from a jsr call. 
     */
    public class RetInstruction extends LocalOperandInstruction {
        // Note: This instruction does not provide any branch targets. The
        // analysis for determining all possible return locations is
        // complicated. Instead, the stack flow analysis assumes that all jsr
        // calls are "well formed", and so it doesn't need to follow the ret
        // back to a "comes from" label. Liveness analysis could take advantage
        // of the branch targets, and reduce the set of variables used to
        // manage jsr return addresses. Since jsr/ret is used infrequently,
        // local variables used by ret are fixed and are not optimized.

        public RetInstruction(LocalVariable local) {
            super(0, local);
            ((LocalVariableImpl)local).setFixedNumber(mNextFixedVariableNumber++);
        }

        public boolean isFlowThrough() {
            return false;
        }

        public byte[] getBytes() {
            int varNum = getVariableNumber();

            if (varNum <= 255) {
                mBytes = new byte[] { Opcode.RET, (byte)varNum };
            } else {
                mBytes = new byte[] 
                { 
                    Opcode.WIDE, 
                    Opcode.RET, 
                    (byte)(varNum >> 8),
                    (byte)varNum
                };
            }

            return mBytes;
        }

        public boolean isLoad() {
            return true;
        }

        public boolean isStore() {
            return false;
        }
    }

    /**
     * Defines a specialized instruction that increments a local variable by
     * a signed 16-bit amount.
     */
    public class ShortIncrementInstruction extends LocalOperandInstruction {
        private short mAmount;

        public ShortIncrementInstruction(LocalVariable local, short amount) {
            super(0, local);
            mAmount = amount;
        }

        public boolean isFlowThrough() {
            return true;
        }

        public byte[] getBytes() {
            int varNum = getVariableNumber();

            if ((-128 <= mAmount && mAmount <= 127) && varNum <= 255) {
                mBytes = new byte[] 
                { Opcode.IINC, 
                  (byte)varNum, 
                  (byte)mAmount 
                };
            } else {
                mBytes = new byte[]
                {
                    Opcode.WIDE,
                    Opcode.IINC,
                    (byte)(varNum >> 8),
                    (byte)varNum,
                    (byte)(mAmount >> 8),
                    (byte)mAmount
                };
            }

            return mBytes;
        }

        public boolean isLoad() {
            return true;
        }

        public boolean isStore() {
            return true;
        }
    }

    /**
     * Defines a switch instruction. The choice of which actual switch 
     * implementation to use (table or lookup switch) is determined 
     * automatically based on which generates to the smallest amount of bytes.
     */
    public class SwitchInstruction extends CodeInstruction {
        private int[] mCases;
        private Location[] mLocations;
        private Location mDefaultLocation;

        private byte mOpcode;

        private int mSmallest;
        private int mLargest;

        public SwitchInstruction(int[] casesParam,
                                 Location[] locationsParam,
                                 Location defaultLocation) {
            // A SwitchInstruction always adjusts the stack by -1 because it 
            // pops the switch key off the stack.
            super(-1);

            if (casesParam.length != locationsParam.length) {
                throw new IllegalArgumentException
                    ("Switch cases and locations sizes differ: " + 
                     casesParam.length + ", " + locationsParam.length);
            }

            mCases = new int[casesParam.length];
            System.arraycopy(casesParam, 0, mCases, 0, casesParam.length);

            mLocations = new Location[locationsParam.length];
            System.arraycopy(locationsParam, 0, mLocations, 
                             0, locationsParam.length);

            mDefaultLocation = defaultLocation;

            // First sort the cases and locations.
            sort(0, mCases.length - 1);

            // Check for duplicate cases.
            int lastCase = 0;
            for (int i=0; i<mCases.length; i++) {
                if (i > 0 && mCases[i] == lastCase) {
                    throw new IllegalArgumentException("Duplicate switch cases: " + lastCase);
                }
                lastCase = mCases[i];
            }

            // Now determine which kind of switch to use.

            mSmallest = mCases[0];
            mLargest = mCases[mCases.length - 1];
            long tSize = 12 + 4 * ((long) mLargest - mSmallest + 1);

            int lSize = 8 + 8 * mCases.length;

            if (tSize <= lSize) {
                mOpcode = Opcode.TABLESWITCH;
            } else {
                mOpcode = Opcode.LOOKUPSWITCH;
            }
        }   

        public Location[] getBranchTargets() {
            Location[] targets = new Location[mLocations.length + 1];
            System.arraycopy(mLocations, 0, targets, 0, mLocations.length);
            targets[targets.length - 1] = mDefaultLocation;

            return targets;
        }

        public boolean isFlowThrough() {
            return false;
        }

        public byte[] getBytes() {
            int length = 1;
            int pad = 3 - (mLocation & 3);
            length += pad;

            if (mOpcode == Opcode.TABLESWITCH) {
                length += 12 + 4 * (mLargest - mSmallest + 1);
            } else {
                length += 8 + 8 * mCases.length;
            }

            mBytes = new byte[length];

            if (!isResolved()) {
                return mBytes;
            }

            mBytes[0] = mOpcode;
            int cursor = pad + 1;

            int defaultOffset = mDefaultLocation.getLocation() - mLocation;
            mBytes[cursor++] = (byte)(defaultOffset >> 24);
            mBytes[cursor++] = (byte)(defaultOffset >> 16);
            mBytes[cursor++] = (byte)(defaultOffset >> 8);
            mBytes[cursor++] = (byte)(defaultOffset >> 0);

            if (mOpcode == Opcode.TABLESWITCH) {
                mBytes[cursor++] = (byte)(mSmallest >> 24);
                mBytes[cursor++] = (byte)(mSmallest >> 16);
                mBytes[cursor++] = (byte)(mSmallest >> 8);
                mBytes[cursor++] = (byte)(mSmallest >> 0);

                mBytes[cursor++] = (byte)(mLargest >> 24);
                mBytes[cursor++] = (byte)(mLargest >> 16);
                mBytes[cursor++] = (byte)(mLargest >> 8);
                mBytes[cursor++] = (byte)(mLargest >> 0);

                int index = 0;
                for (int case_ = mSmallest; case_ <= mLargest; case_++) {
                    if (case_ == mCases[index]) {
                        int offset = 
                            mLocations[index].getLocation() - mLocation;
                        mBytes[cursor++] = (byte)(offset >> 24);
                        mBytes[cursor++] = (byte)(offset >> 16);
                        mBytes[cursor++] = (byte)(offset >> 8);
                        mBytes[cursor++] = (byte)(offset >> 0);

                        index++;
                    } else {
                        mBytes[cursor++] = (byte)(defaultOffset >> 24);
                        mBytes[cursor++] = (byte)(defaultOffset >> 16);
                        mBytes[cursor++] = (byte)(defaultOffset >> 8);
                        mBytes[cursor++] = (byte)(defaultOffset >> 0);
                    }
                }
            } else {
                mBytes[cursor++] = (byte)(mCases.length >> 24);
                mBytes[cursor++] = (byte)(mCases.length >> 16);
                mBytes[cursor++] = (byte)(mCases.length >> 8);
                mBytes[cursor++] = (byte)(mCases.length >> 0);

                for (int index = 0; index < mCases.length; index++) {
                    int case_ = mCases[index];

                    mBytes[cursor++] = (byte)(case_ >> 24);
                    mBytes[cursor++] = (byte)(case_ >> 16);
                    mBytes[cursor++] = (byte)(case_ >> 8);
                    mBytes[cursor++] = (byte)(case_ >> 0);

                    int offset = mLocations[index].getLocation() - mLocation;
                    mBytes[cursor++] = (byte)(offset >> 24);
                    mBytes[cursor++] = (byte)(offset >> 16);
                    mBytes[cursor++] = (byte)(offset >> 8);
                    mBytes[cursor++] = (byte)(offset >> 0);
                }
            }

            return mBytes;
        }

        public boolean isResolved() {
            if (mDefaultLocation.getLocation() >= 0) {
                for (int i=0; i<mLocations.length; i++) {
                    if (mLocations[i].getLocation() < 0) {
                        break;
                    }
                }

                return true;
            }

            return false;
        }

        private void sort(int left, int right) {
            if (left >= right) {
                return;
            }

            swap(left, (left + right) / 2); // move middle element to 0

            int last = left;

            for (int i = left + 1; i <= right; i++) {
                if (mCases[i] < mCases[left]) {
                    swap(++last, i);
                }
            }

            swap(left, last);
            sort(left, last-1);
            sort(last + 1, right);
        }

        private void swap(int i, int j) {
            int tempInt = mCases[i];
            mCases[i] = mCases[j];
            mCases[j] = tempInt;

            Location tempLocation = mLocations[i];
            mLocations[i] = mLocations[j];
            mLocations[j] = tempLocation;
        }
    }
}
