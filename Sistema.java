
// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Versão com paginação mínima: GM + loader paginado + tradução centralizada (Fatorial apenas)

import java.util.*;

public class Sistema {

    // ======================= PARÂMETROS DO SISTEMA =======================
    // tamanho da página (em palavras). Pode variar; 8 é um bom valor de teste.
    private static final int TAM_PG = 8;

    // ======================= H A R D W A R E =============================
    public class Memory {
        public Word[] pos; // pos[i] é a posição i da memória. cada posição é uma palavra.

        public Memory(int size) {
            pos = new Word[size];
            for (int i = 0; i < pos.length; i++) {
                pos[i] = new Word(Opcode.___, -1, -1, -1);
            }
        }
    }

    public class Word {    // cada posicao da memoria tem uma instrucao (ou um dado)
        public Opcode opc; //
        public int ra;     // indice do primeiro registrador da operacao (Rs ou Rd cfe opcode na tabela)
        public int rb;     // indice do segundo registrador da operacao (Rc ou Rs cfe operacao)
        public int p;      // parametro para instrucao (k ou A cfe operacao), ou o dado, se opcode = DADO

        public Word(Opcode _opc, int _ra, int _rb, int _p) { // vide definição da VM - colunas vermelhas da tabela
            opc = _opc;
            ra = _ra;
            rb = _rb;
            p  = _p;
        }
    }

    // ======================= C P U =======================================
    public enum Opcode {
        DATA, ___,              // se memoria nesta posicao tem um dado, usa DATA, se nao usada é NULO ___
        JMP, JMPI, JMPIG, JMPIL, JMPIE, // desvios
        JMPIM, JMPIGM, JMPILM, JMPIEM,
        JMPIGK, JMPILK, JMPIEK, JMPIGT,
        ADDI, SUBI, ADD, SUB, MULT,    // matematicos
        LDI, LDD, STD, LDX, STX, MOVE, // movimentacao
        SYSCALL, STOP                  // chamada de sistema e parada
    }

    public enum Interrupts {           // possiveis interrupcoes que esta CPU gera
        noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow;
    }

    public class CPU {
        private int maxInt; // valores maximo e minimo para inteiros nesta cpu
        private int minInt;
        // CONTEXTO da CPU ...
        private int pc;     // program counter (lógico)
        private Word ir;    // instruction register,
        private int[] reg;  // registradores da CPU
        private Interrupts irpt; // durante instrucao, interrupcao pode ser sinalizada
        // FIM CONTEXTO

        private Word[] m;   // memória física

        // MMU simplificada
        private int tamPg = TAM_PG;
        private int[] tabelaPaginas = null; // tabela do processo atual

        private InterruptHandling ih;    // handlers
        private SysCallHandling sysCall;

        private boolean cpuStop;    // flag para parar CPU
        private boolean debug;      // se true, mostra cada instrucao
        private Utilities u;        // para dump

        public CPU(Memory _mem, boolean _debug) {
            maxInt = 32767;
            minInt = -32767;
            m = _mem.pos;
            reg = new int[10];
            debug = _debug;
        }

        public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) {
            ih = _ih;
            sysCall = _sysCall;
        }

        public void setUtilities(Utilities _u) { u = _u; }

        // ======== MMU API =========
        public void setMMU(int[] _tabelaPaginas, int _tamPg) {
            this.tabelaPaginas = _tabelaPaginas;
            this.tamPg = _tamPg;
        }

        // traduz endereço lógico -> físico. Se inválido, seta interrupção e retorna -1
        public int toPhysical(int endLogico) {
            if (tabelaPaginas == null) { // sem paginação: identidade
                return endLogico;
            }
            if (endLogico < 0) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            int pag = endLogico / tamPg;
            int off = endLogico % tamPg;
            if (pag < 0 || pag >= tabelaPaginas.length) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            int frame = tabelaPaginas[pag];
            if (frame < 0) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            int base = frame * tamPg;
            int endFis = base + off;
            if (endFis < 0 || endFis >= m.length) { irpt = Interrupts.intEnderecoInvalido; return -1; }
            return endFis;
        }

        // checa endereço físico
        private boolean legalFisico(int e) {
            if (e >= 0 && e < m.length) {
                return true;
            } else {
                irpt = Interrupts.intEnderecoInvalido;
                return false;
            }
        }

        public void setContext(int _pc) { // pc lógico
            pc = _pc;
            irpt = Interrupts.noInterrupt;
        }

        public void run() {
            cpuStop = false;
            while (!cpuStop) {
                // FETCH (usa tradução)
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

                    // EXEC
                    switch (ir.opc) {
                        // Movimentação e memória
                        case LDI: // Rd <- k
                            reg[ir.ra] = ir.p; pc++; break;

                        case LDD: { // Rd <- [A]  (A = lógico)
                            int a = toPhysical(ir.p);
                            if (legalFisico(a)) { reg[ir.ra] = m[a].p; pc++; }
                        } break;

                        case LDX: { // RD <- [RS]  (RS contém lógico)
                            int a = toPhysical(reg[ir.rb]);
                            if (legalFisico(a)) { reg[ir.ra] = m[a].p; pc++; }
                        } break;

                        case STD: { // [A] <- Rs   (A lógico)
                            int a = toPhysical(ir.p);
                            if (legalFisico(a)) {
                                m[a].opc = Opcode.DATA;
                                m[a].p = reg[ir.ra];
                                pc++;
                                if (debug) { System.out.print("                                  "); u.dump(a,a+1); }
                            }
                        } break;

                        case STX: { // [Rd] <- Rs  (Rd contém lógico)
                            int a = toPhysical(reg[ir.ra]);
                            if (legalFisico(a)) { m[a].opc = Opcode.DATA; m[a].p = reg[ir.rb]; pc++; }
                        } break;

                        case MOVE: // RD <- RS
                            reg[ir.ra] = reg[ir.rb]; pc++; break;

                        // Aritméticas
                        case ADD:  reg[ir.ra] = reg[ir.ra] + reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
                        case ADDI: reg[ir.ra] = reg[ir.ra] + ir.p;        testOverflow(reg[ir.ra]); pc++; break;
                        case SUB:  reg[ir.ra] = reg[ir.ra] - reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
                        case SUBI: reg[ir.ra] = reg[ir.ra] - ir.p;        testOverflow(reg[ir.ra]); pc++; break;
                        case MULT: reg[ir.ra] = reg[ir.ra] * reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;

                        // Jumps (pc é lógico)
                        case JMP:    pc = ir.p; break;
                        case JMPI:   pc = reg[ir.ra]; break;
                        case JMPIG:  pc = (reg[ir.rb] > 0) ? reg[ir.ra] : pc+1; break;
                        case JMPIL:  pc = (reg[ir.rb] < 0) ? reg[ir.ra] : pc+1; break;
                        case JMPIE:  pc = (reg[ir.rb] == 0) ? reg[ir.ra] : pc+1; break;
                        case JMPIGK: pc = (reg[ir.rb] > 0) ? ir.p : pc+1; break;
                        case JMPILK: pc = (reg[ir.rb] < 0) ? ir.p : pc+1; break;
                        case JMPIEK: pc = (reg[ir.rb] == 0) ? ir.p : pc+1; break;

                        case JMPIM: { // PC <- [A] (A lógico)
                            int a = toPhysical(ir.p);
                            if (legalFisico(a)) pc = m[a].p;
                        } break;
                        case JMPIGM: { // if RC>0 PC <- [A] else PC++
                            int a = toPhysical(ir.p);
                            if (legalFisico(a)) pc = (reg[ir.rb] > 0) ? m[a].p : pc+1;
                        } break;
                        case JMPILM: { // if RC<0 PC <- [A] else PC++
                            int a = toPhysical(ir.p);
                            if (legalFisico(a)) pc = (reg[ir.rb] < 0) ? m[a].p : pc+1;
                        } break;
                        case JMPIEM: { // if RC==0 PC <- [A] else PC++
                            int a = toPhysical(ir.p);
                            if (legalFisico(a)) pc = (reg[ir.rb] == 0) ? m[a].p : pc+1;
                        } break;

                        case DATA: // pc está sobre área supostamente de dados
                            irpt = Interrupts.intInstrucaoInvalida;
                            break;

                        case SYSCALL:
                            sysCall.handle();
                            pc++;
                            break;

                        case STOP:
                            sysCall.stop();
                            cpuStop = true;
                            break;

                        default:
                            irpt = Interrupts.intInstrucaoInvalida;
                            break;
                    }
                }

                if (irpt != Interrupts.noInterrupt) { // interrupção (fim nesta versão)
                    ih.handle(irpt);
                    cpuStop = true;
                }
            }
        }

        private boolean testOverflow(int v) {
            if ((v < minInt) || (v > maxInt)) { irpt = Interrupts.intOverflow; return false; }
            return true;
        }
    }
    // ======================= C P U - fim =================================

    // ======================= HW - conjunto =================================
    public class HW {
        public Memory mem;
        public CPU cpu;

        public HW(int tamMem) {
            mem = new Memory(tamMem);
            cpu = new CPU(mem, true); // true liga debug
        }
    }

    // ======================= S O ==========================================
    public class InterruptHandling {
        private HW hw; // referencia ao hw se tiver que setar algo
        public InterruptHandling(HW _hw) { hw = _hw; }
        public void handle(Interrupts irpt) {
            System.out.println("                                               Interrupcao " + irpt);
        }
    }

    public class SysCallHandling {
        private HW hw; // referencia ao hw se tiver que setar algo
        public SysCallHandling(HW _hw) { hw = _hw; }
        public void stop() { System.out.println("                                               SYSCALL STOP"); }
        public void handle() { // reg[8] = 1(entrada) | 2(saida); reg[9] = endereço lógico
            System.out.println("SYSCALL pars:  " + hw.cpu.reg[8] + " / " + hw.cpu.reg[9]);
            if (hw.cpu.reg[8]==1){
                // entrada (não implementada nesta versão mínima)
            } else if (hw.cpu.reg[8]==2){
                int fis = hw.cpu.toPhysical(hw.cpu.reg[9]);
                if (fis >= 0) {
                    System.out.println("OUT:   "+ hw.mem.pos[fis].p);
                }
            } else { System.out.println("  PARAMETRO INVALIDO"); }
        }
    }

    // ======================= GM (Gerente de Memória) ======================
    public interface GM {
        // retorna a tabela de páginas se conseguir alocar, ou null caso contrário
        int[] aloca(int nroPalavras);
        void desaloca(int[] tabelaPaginas);
    }

    public class SimplePagingGM implements GM {
        private final int tamMem;
        private final int tamPg;
        private final int qtdFrames;
        private final boolean[] livre; // true = livre; false = ocupado

        public SimplePagingGM(int tamMemPalavras, int tamPgPalavras) {
            this.tamMem = tamMemPalavras;
            this.tamPg = tamPgPalavras;
            this.qtdFrames = tamMem / tamPg;
            this.livre = new boolean[qtdFrames];
            Arrays.fill(livre, true);
        }

        @Override
        public int[] aloca(int nroPalavras) {
            if (nroPalavras <= 0) return new int[0];
            int paginasNec = (nroPalavras + tamPg - 1) / tamPg;
            List<Integer> frames = new ArrayList<>();
            for (int f = 0; f < qtdFrames && frames.size() < paginasNec; f++) {
                if (livre[f]) frames.add(f);
            }
            if (frames.size() < paginasNec) return null; // não há frames suficientes
            int[] tabela = new int[paginasNec];
            for (int i = 0; i < paginasNec; i++) {
                int f = frames.get(i);
                tabela[i] = f;
                livre[f] = false;
            }
            return tabela;
        }

        @Override
        public void desaloca(int[] tabelaPaginas) {
            if (tabelaPaginas == null) return;
            for (int f : tabelaPaginas) {
                if (f >= 0 && f < livre.length) livre[f] = true;
            }
        }
    }

    // ======================= UTILITÁRIOS ==================================
    public class Utilities {
        private HW hw;
        private GM gm;

        public Utilities(HW _hw, GM _gm) {
            hw = _hw;
            gm = _gm;
        }

        // carrega programa em frames alocados conforme tabelaPaginas
        private void loadProgramPaged(Word[] prog, int[] tabelaPaginas, int tamPg) {
            Word[] m = hw.mem.pos; // física
            for (int i = 0; i < prog.length; i++) {
                int pag = i / tamPg;
                int off = i % tamPg;
                int frame = tabelaPaginas[pag];
                int base = frame * tamPg;
                int fis = base + off;
                m[fis].opc = prog[i].opc;
                m[fis].ra  = prog[i].ra;
                m[fis].rb  = prog[i].rb;
                m[fis].p   = prog[i].p;
            }
        }

        // dump da memória (faixa física)
        public void dump(Word w) {
            System.out.print("[ " + w.opc + ", " + w.ra + ", " + w.rb + ", " + w.p + " ] ");
        }

        public void dump(int ini, int fim) {
            Word[] m = hw.mem.pos;
            for (int i = ini; i < fim; i++) {
                System.out.print(i + ":  ");
                dump(m[i]);
                System.out.println();
            }
        }

        private void loadAndExecPaged(Word[] prog) {
            int tamMem = hw.mem.pos.length;
            int[] tabela = gm.aloca(prog.length);
            if (tabela == null) {
                System.out.println("Falha de alocação: memória insuficiente.");
                return;
            }

            loadProgramPaged(prog, tabela, TAM_PG);
            // instala MMU no CPU
            hw.cpu.setMMU(tabela, TAM_PG);

            System.out.println("---------------------------------- programa carregado (paginado)");
            // opcional: dump físico das primeiras páginas carregadas
            // dump(0, Math.min(tamMem, 4*TAM_PG));

            hw.cpu.setContext(0); // PC lógico inicia em 0
            System.out.println("---------------------------------- inicia execucao ");
            hw.cpu.run();
            System.out.println("---------------------------------- fim execucao ");

            gm.desaloca(tabela); // libera frames (nesta versão simples, ao final)
        }
    }

    // ======================= SO ==========================================
    public class SO {
        public InterruptHandling ih;
        public SysCallHandling sc;
        public Utilities utils;
        public GM gm;

        public SO(HW hw) {
            ih = new InterruptHandling(hw);
            sc = new SysCallHandling(hw);
            hw.cpu.setAddressOfHandlers(ih, sc);

            gm = new SimplePagingGM(hw.mem.pos.length, TAM_PG);
            utils = new Utilities(hw, gm);
        }
    }

    // ======================= SISTEMA =====================================
    public HW hw;
    public SO so;
    public Programs progs;

    public Sistema(int tamMem) {
        hw = new HW(tamMem);
        so = new SO(hw);
        hw.cpu.setUtilities(so.utils);
        progs = new Programs();
    }

    public void run() {
        // smoke test só com FATORIAL
        so.utils.loadAndExecPaged(progs.retrieveProgram("fatorial"));
    }

    public static void main(String args[]) {
        Sistema s = new Sistema(1024);
        s.run();
    }

    // ======================= PROGRAMAS ===================================
    public class Program {
        public String name;
        public Word[] image;
        public Program(String n, Word[] i) { name = n; image = i; }
    }

    public class Programs {
        public Word[] retrieveProgram(String pname) {
            for (Program p : progs) if (p != null && p.name.equals(pname)) return p.image;
            return null;
        }

        public Program[] progs = {
                new Program("fatorial",
                        new Word[] {
                                // fatorial simples: entrada fixa em r0 = 7; resultado vai para posição lógica 10
                                new Word(Opcode.LDI, 0, -1, 7),  // 0
                                new Word(Opcode.LDI, 1, -1, 1),  // 1
                                new Word(Opcode.LDI, 6, -1, 1),  // 2
                                new Word(Opcode.LDI, 7, -1, 8),  // 3 (fim)
                                new Word(Opcode.JMPIE, 7, 0, 0), // 4
                                new Word(Opcode.MULT, 1, 0, -1), // 5
                                new Word(Opcode.SUB, 0, 6, -1),  // 6
                                new Word(Opcode.JMP, -1, -1, 4), // 7
                                new Word(Opcode.STD, 1, -1, 10), // 8
                                new Word(Opcode.STOP, -1, -1, -1), // 9
                                new Word(Opcode.DATA, -1, -1, -1)  // 10 (resultado)
                        })
        };
    }
}
