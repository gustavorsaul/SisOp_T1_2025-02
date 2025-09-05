import java.util.Arrays;

// Classe "container" para todos os componentes de hardware.
public class Hardware {

    // Representa a Unidade Central de Processamento (CPU).
    public static class CPU {
        public enum Opcode {
            DATA, ___, JMP, JMPI, JMPIG, JMPIL, JMPIE, JMPIM, JMPIGM, JMPILM, JMPIEM,
            JMPIGK, JMPILK, JMPIEK, JMPIGT, ADDI, SUBI, ADD, SUB, MULT,
            LDI, LDD, STD, LDX, STX, MOVE, SYSCALL, STOP
        }

        public enum Interrupts {
            noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow, intQuantumEnd
        }

        private int maxInt, minInt;
        private int pc;
        private Word ir;
        private int[] reg;
        private Interrupts irpt;
        private Word[] m;
        private int tamPg;
        private int[] tabelaPaginas = null;
        // ATUALIZADO: Tipos de referência para as novas classes do SO
        private SisOp.InterruptHandling ih;
        private SisOp.SysCallHandling sysCall;
        private boolean cpuStop;
        private boolean debug;
        private SisOp.Utilities u;
        private int instructionCounter;

        public CPU(Memory _mem, boolean _debug, int tamPag) {
            this.maxInt = 32767; this.minInt = -32767;
            this.m = _mem.pos; this.reg = new int[10]; this.debug = _debug;
            this.instructionCounter = 0; this.irpt = Interrupts.noInterrupt;
            this.tamPg = tamPag;
        }

        public void setDebug(boolean _debug) { this.debug = _debug; }
        // ATUALIZADO: Tipos de referência para as novas classes do SO
        public void setAddressOfHandlers(SisOp.InterruptHandling _ih, SisOp.SysCallHandling _sysCall) { this.ih = _ih; this.sysCall = _sysCall; }
        public void setUtilities(SisOp.Utilities _u) { this.u = _u; }
        public void setMMU(int[] _tabelaPaginas) { this.tabelaPaginas = _tabelaPaginas; }
        public int getContextPC() { return pc; }
        public int[] getContextRegs() { return Arrays.copyOf(reg, reg.length); }
        public void setContext(int _pc, int[] _regs) { this.pc = _pc; this.reg = Arrays.copyOf(_regs, _regs.length); }
        public void resetInstructionCounter() { this.instructionCounter = 0; }
        
        public int toPhysical(int endLogico) {
            if (tabelaPaginas == null) return endLogico;
            if (endLogico < 0) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            int pag = endLogico / tamPg; int off = endLogico % tamPg;
            if (pag < 0 || pag >= tabelaPaginas.length) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            int frame = tabelaPaginas[pag];
            if (frame < 0) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            int endFis = frame * tamPg + off;
            if (endFis < 0 || endFis >= m.length) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            return endFis;
        }

        private boolean legalFisico(int e) {
            if (e >= 0 && e < m.length) return true;
            else { irpt = Interrupts.intEnderecoInvalido; return false; }
        }

        public void step(int quantum) {
            if (cpuStop) return;
            int pcFis = toPhysical(pc);
            if (legalFisico(pcFis)) {
                ir = m[pcFis];
                if (debug) {
                    System.out.print("                                              regs: ");
                    for (int i = 0; i < 10; i++) System.out.print(" r[" + i + "]:" + reg[i]);
                    System.out.println();
                    System.out.print("                      pc(log) " + pc + " -> pc(fis) " + pcFis + "       exec: ");
                    u.dump(ir);
                }
                switch (ir.opc) {
                    case LDI: reg[ir.r1] = ir.p; pc++; break;
                    case LDD: { int a = toPhysical(ir.p); if (legalFisico(a)) { reg[ir.r1] = m[a].p; pc++; } } break;
                    case LDX: { int a = toPhysical(reg[ir.r2]); if (legalFisico(a)) { reg[ir.r1] = m[a].p; pc++; } } break;
                    case STD: { int a = toPhysical(ir.p); if (legalFisico(a)) { m[a].opc = Opcode.DATA; m[a].p = reg[ir.r1]; pc++; } } break;
                    case STX: { int a = toPhysical(reg[ir.r1]); if (legalFisico(a)) { m[a].opc = Opcode.DATA; m[a].p = reg[ir.r2]; pc++; } } break;
                    case MOVE: reg[ir.r1] = reg[ir.r2]; pc++; break;
                    case ADD: reg[ir.r1] += reg[ir.r2]; testOverflow(reg[ir.r1]); pc++; break;
                    case ADDI: reg[ir.r1] += ir.p; testOverflow(reg[ir.r1]); pc++; break;
                    case SUB: reg[ir.r1] -= reg[ir.r2]; testOverflow(reg[ir.r1]); pc++; break;
                    case SUBI: reg[ir.r1] -= ir.p; testOverflow(reg[ir.r1]); pc++; break;
                    case MULT: reg[ir.r1] *= reg[ir.r2]; testOverflow(reg[ir.r1]); pc++; break;
                    case JMP: pc = ir.p; break;
                    case JMPI: pc = reg[ir.r1]; break;
                    case JMPIG: pc = (reg[ir.r2] > 0) ? reg[ir.r1] : pc + 1; break;
                    case JMPIL: pc = (reg[ir.r2] < 0) ? reg[ir.r1] : pc + 1; break;
                    case JMPIE: pc = (reg[ir.r2] == 0) ? reg[ir.r1] : pc + 1; break;
                    case JMPIGK: pc = (reg[ir.r2] > 0) ? ir.p : pc + 1; break;
                    case JMPILK: pc = (reg[ir.r2] < 0) ? ir.p : pc + 1; break;
                    case JMPIEK: pc = (reg[ir.r2] == 0) ? ir.p : pc + 1; break;
                    case JMPIM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = m[a].p; } break;
                    case JMPIGM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = (reg[ir.r2] > 0) ? m[a].p : pc + 1; } break;
                    case JMPILM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = (reg[ir.r2] < 0) ? m[a].p : pc + 1; } break;
                    case JMPIEM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = (reg[ir.r2] == 0) ? m[a].p : pc + 1; } break;
                    case DATA: irpt = Interrupts.intInstrucaoInvalida; break;
                    case SYSCALL: sysCall.handle(); pc++; break;
                    case STOP: sysCall.stop(); break;
                    default: irpt = Interrupts.intInstrucaoInvalida; break;
                }
            }
            if (irpt == Interrupts.noInterrupt) {
                instructionCounter++;
                if (instructionCounter >= quantum) { irpt = Interrupts.intQuantumEnd; }
            }
            if (irpt != Interrupts.noInterrupt) {
                ih.handle(irpt);
                irpt = Interrupts.noInterrupt;
            }
        }
        public void stop() { this.cpuStop = true; }
        public void start() { this.cpuStop = false; }
        private boolean testOverflow(int v) { if ((v < minInt) || (v > maxInt)) { irpt = Interrupts.intOverflow; return false; } return true; }
    }

    public static class Memory {
        public Word[] pos;

        public Memory(int size) {
            this.pos = new Word[size];
            for (int i = 0; i < pos.length; i++) {
                pos[i] = new Word(CPU.Opcode.___, -1, -1, -1);
            }
        }
    }

    public static class Word {
        public CPU.Opcode opc;
        public int r1;
        public int r2;
        public int p;

        public Word(CPU.Opcode _opc, int _r1, int _r2, int _p) {
            this.opc = _opc; this.r1 = _r1; this.r2 = _r2; this.p = _p;
        }
    }
    
    public static class HW {
        public Memory mem;
        public CPU cpu;

        public HW(int tamMem, int tamPag) {
            this.mem = new Memory(tamMem);
            this.cpu = new CPU(this.mem, false, tamPag);
        }
    }
}