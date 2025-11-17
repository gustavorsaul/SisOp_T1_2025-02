import java.util.Arrays;

public class Hardware {

    // Entrada da tabela de páginas para paginação sob demanda
    public static class PageTableEntry {
        public int frameNumber = -1;
        public boolean valid = false;
        public boolean onDisk = false;
        public int diskAddress = -1;
    }

    // Simulador da CPU com suporte a paginação e interrupções
    public static class CPU {
        public enum Opcode {
            DATA, ___, JMP, JMPI, JMPIG, JMPIL, JMPIE, JMPIM, JMPIGM, JMPILM, JMPIEM,
            JMPIGK, JMPILK, JMPIEK, JMPIGT, ADDI, SUBI, ADD, SUB, MULT,
            LDI, LDD, STD, LDX, STX, MOVE, SYSCALL, STOP
        }

        public enum Interrupts {
            noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow, intQuantumEnd, 
            intIO,
            intPageFault
        }

        private int maxInt, minInt;
        private int pc;
        private Word ir;
        private int[] reg;
        private Interrupts irpt;
        private Word[] m;
        private int tamPg;
        private PageTableEntry[] tabelaPaginas = null; 
        private SisOp.InterruptHandling ih;
        private SisOp.SysCallHandling sysCall;
        private boolean cpuStop;
        private boolean debug;
        private SisOp.Utilities u;
        private int instructionCounter;
        
        private int faultedPage = -1;

        public CPU(Memory _mem, boolean _debug, int tamPag) {
            this.maxInt = 32767;
            this.minInt = -32767;
            this.m = _mem.pos;
            this.reg = new int[10];
            this.debug = _debug;
            this.instructionCounter = 0;
            this.irpt = Interrupts.noInterrupt;
            this.tamPg = tamPag;
        }

        public void setDebug(boolean _debug) {
            this.debug = _debug;
        }

        public void setAddressOfHandlers(SisOp.InterruptHandling _ih, SisOp.SysCallHandling _sysCall) {
            this.ih = _ih;
            this.sysCall = _sysCall;
        }

        public void setUtilities(SisOp.Utilities _u) {
            this.u = _u;
        }

        // Define a tabela de páginas do processo atual
        public void setMMU(PageTableEntry[] _tabelaPaginas) {
            this.tabelaPaginas = _tabelaPaginas;
        }
        
        public int getFaultedPage() {
            return this.faultedPage;
        }

        public int getContextPC() {
            return pc;
        }

        public int[] getContextRegs() {
            return Arrays.copyOf(reg, reg.length);
        }

        public void setContext(int _pc, int[] _regs) {
            this.pc = _pc;
            this.reg = Arrays.copyOf(_regs, _regs.length);
        }

        public void resetInstructionCounter() {
            this.instructionCounter = 0;
        }

        public void triggerIOInterrupt() {
            this.irpt = Interrupts.intIO;
        }

        public void triggerPageFault(int page) {
            this.faultedPage = page;
            this.irpt = Interrupts.intPageFault;
        }

        // Traduz endereço lógico para físico usando tabela de páginas
        public int toPhysical(int endLogico) {
            if (tabelaPaginas == null) return endLogico;
            if (endLogico < 0) {
                irpt = Interrupts.intEnderecoInvalido;
                return -1;
            }
            int pag = endLogico / tamPg;
            int off = endLogico % tamPg;
            if (pag < 0 || pag >= tabelaPaginas.length) {
                irpt = Interrupts.intEnderecoInvalido;
                return -1;
            }
            
            PageTableEntry entry = tabelaPaginas[pag];

            if (!entry.valid) {
                this.faultedPage = pag;
                irpt = Interrupts.intPageFault;
                return -1;
            }
            
            int frame = entry.frameNumber;
            if (frame < 0) {
                irpt = Interrupts.intEnderecoInvalido;
                return -1;
            }

            int endFis = frame * tamPg + off;
            if (endFis < 0 || endFis >= m.length) {
                irpt = Interrupts.intEnderecoInvalido;
                return -1;
            }
            return endFis;
        }

        // Verifica se endereço físico é válido
        private boolean legalFisico(int e) {
            if (e >= 0 && e < m.length) return true;
            else {
                irpt = Interrupts.intEnderecoInvalido;
                return false;
            }
        }

        public void step(int quantum) {
            if (cpuStop) return;

            if (irpt != Interrupts.noInterrupt) {
                Interrupts currentIrpt = irpt;
                irpt = Interrupts.noInterrupt;
                ih.handle(currentIrpt);
                if (cpuStop) return;
                if (cpuStop) return; 
            }

            int pcFis = toPhysical(pc);
            
            if (irpt == Interrupts.intPageFault) {
                return; 
            }

            if (legalFisico(pcFis)) {
                ir = m[pcFis];
                if (debug) {
                    System.out.print("\nregs: ");
                    for (int i = 0; i < 10; i++) System.out.print(" r[" + i + "]:" + reg[i]);
                    System.out.println();
                    System.out.print("pc(log) " + pc + " -> pc(fis) " + pcFis + "       exec: ");
                    System.out.print("\n------------------------------------------------------------");
                    u.dump(ir);
                }
                
                int oldPC = pc; 
                
                switch (ir.opc) {
                    case LDI:
                        reg[ir.r1] = ir.p;
                        pc++;
                        break;
                    case LDD: {
                        int a = toPhysical(ir.p);
                        if (irpt != Interrupts.noInterrupt) break;
                        if (legalFisico(a)) {
                            reg[ir.r1] = m[a].p;
                            pc++;
                        }
                    }
                        break;
                    case LDX: {
                        int a = toPhysical(reg[ir.r2]);
                        if (irpt != Interrupts.noInterrupt) break;
                        if (legalFisico(a)) {
                            reg[ir.r1] = m[a].p;
                            pc++;
                        }
                    }
                        break;
                    case STD: {
                        int a = toPhysical(ir.p);
                        if (irpt != Interrupts.noInterrupt) break;
                        if (legalFisico(a)) {
                            m[a].opc = Opcode.DATA;
                            m[a].p = reg[ir.r1];
                            pc++;
                        }
                    }
                        break;
                    case STX: {
                        int a = toPhysical(reg[ir.r1]);
                        if (irpt != Interrupts.noInterrupt) break;
                        if (legalFisico(a)) {
                            m[a].opc = Opcode.DATA;
                            m[a].p = reg[ir.r2];
                            pc++;
                        }
                    }
                        break;
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
                    case JMPIM: { int a = toPhysical(ir.p); if (irpt != Interrupts.noInterrupt) break; if (legalFisico(a)) pc = m[a].p; } break;
                    case JMPIGM: { int a = toPhysical(ir.p); if (irpt != Interrupts.noInterrupt) break; if (legalFisico(a)) pc = (reg[ir.r2] > 0) ? m[a].p : pc + 1; } break;
                    case JMPILM: { int a = toPhysical(ir.p); if (irpt != Interrupts.noInterrupt) break; if (legalFisico(a)) pc = (reg[ir.r2] < 0) ? m[a].p : pc + 1; } break;
                    case JMPIEM: { int a = toPhysical(ir.p); if (irpt != Interrupts.noInterrupt) break; if (legalFisico(a)) pc = (reg[ir.r2] == 0) ? m[a].p : pc + 1; } break;

                    case DATA: irpt = Interrupts.intInstrucaoInvalida; break;
                    case SYSCALL:
                        sysCall.handle(); 
                        break;
                    case STOP:
                        sysCall.stop();
                        break;
                    default:
                        irpt = Interrupts.intInstrucaoInvalida;
                        break;
                }
                
                if (irpt == Interrupts.intPageFault) {
                    pc = oldPC;
                }
            }

            if (irpt == Interrupts.noInterrupt) {
                instructionCounter++;
                if (instructionCounter >= quantum) {
                    irpt = Interrupts.intQuantumEnd;
                }
            }
        }

        public void stop() { this.cpuStop = true; }
        public void start() { this.cpuStop = false; }

        // Verifica overflow aritmético
        private boolean testOverflow(int v) {
            if ((v < minInt) || (v > maxInt)) {
                irpt = Interrupts.intOverflow;
                return false;
            }
            return true;
        }
    }

    // Memória RAM simulada
    public static class Memory {
        public Word[] pos;
        public Memory(int size) {
            this.pos = new Word[size];
            for (int i = 0; i < pos.length; i++) {
                pos[i] = new Word(CPU.Opcode.___, -1, -1, -1);
            }
        }
    }

    // Palavra de memória (instrução ou dado)
    public static class Word {
        public CPU.Opcode opc;
        public int r1;
        public int r2;
        public int p;
        public Word(CPU.Opcode _opc, int _r1, int _r2, int _p) {
            this.opc = _opc; this.r1 = _r1; this.r2 = _r2; this.p = _p;
        }
    }

    // Hardware completo: memória e CPU
    public static class HW {
        public Memory mem;
        public CPU cpu;
        public HW(int tamMem, int tamPag) {
            this.mem = new Memory(tamMem);
            this.cpu = new CPU(this.mem, false, tamPag);
        }
    }
}