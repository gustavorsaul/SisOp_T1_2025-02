// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Versão com paginação mínima: GM + loader paginado + tradução centralizada (Fatorial apenas)
// MODIFICADO PARA INCLUIR GERENTE DE PROCESSOS (GP) E SHELL INTERATIVO

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
        
        // NOVO: Método para ligar/desligar o modo trace
        public void setDebug(boolean _debug){
            this.debug = _debug;
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
        
        // NOVO: Método para salvar o contexto de um processo (seu PC)
        public int getContext() {
            return pc;
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
                        case LDI: reg[ir.ra] = ir.p; pc++; break;
                        case LDD: { int a = toPhysical(ir.p); if (legalFisico(a)) { reg[ir.ra] = m[a].p; pc++; } } break;
                        case LDX: { int a = toPhysical(reg[ir.rb]); if (legalFisico(a)) { reg[ir.ra] = m[a].p; pc++; } } break;
                        case STD: { int a = toPhysical(ir.p); if (legalFisico(a)) { m[a].opc = Opcode.DATA; m[a].p = reg[ir.ra]; pc++; if (debug) { System.out.print("                                  "); u.dump(a,a+1); } } } break;
                        case STX: { int a = toPhysical(reg[ir.ra]); if (legalFisico(a)) { m[a].opc = Opcode.DATA; m[a].p = reg[ir.rb]; pc++; } } break;
                        case MOVE: reg[ir.ra] = reg[ir.rb]; pc++; break;
                        // Aritméticas
                        case ADD:  reg[ir.ra] = reg[ir.ra] + reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
                        case ADDI: reg[ir.ra] = reg[ir.ra] + ir.p;        testOverflow(reg[ir.ra]); pc++; break;
                        case SUB:  reg[ir.ra] = reg[ir.ra] - reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
                        case SUBI: reg[ir.ra] = reg[ir.ra] - ir.p;        testOverflow(reg[ir.ra]); pc++; break;
                        case MULT: reg[ir.ra] = reg[ir.ra] * reg[ir.rb]; testOverflow(reg[ir.ra]); pc++; break;
                        // Jumps
                        case JMP:    pc = ir.p; break;
                        case JMPI:   pc = reg[ir.ra]; break;
                        case JMPIG:  pc = (reg[ir.rb] > 0) ? reg[ir.ra] : pc+1; break;
                        case JMPIL:  pc = (reg[ir.rb] < 0) ? reg[ir.ra] : pc+1; break;
                        case JMPIE:  pc = (reg[ir.rb] == 0) ? reg[ir.ra] : pc+1; break;
                        case JMPIGK: pc = (reg[ir.rb] > 0) ? ir.p : pc+1; break;
                        case JMPILK: pc = (reg[ir.rb] < 0) ? ir.p : pc+1; break;
                        case JMPIEK: pc = (reg[ir.rb] == 0) ? ir.p : pc+1; break;
                        case JMPIM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = m[a].p; } break;
                        case JMPIGM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = (reg[ir.rb] > 0) ? m[a].p : pc+1; } break;
                        case JMPILM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = (reg[ir.rb] < 0) ? m[a].p : pc+1; } break;
                        case JMPIEM: { int a = toPhysical(ir.p); if (legalFisico(a)) pc = (reg[ir.rb] == 0) ? m[a].p : pc+1; } break;
                        // Especiais
                        case DATA: irpt = Interrupts.intInstrucaoInvalida; break;
                        case SYSCALL: sysCall.handle(); pc++; break;
                        case STOP: sysCall.stop(); cpuStop = true; break;
                        default: irpt = Interrupts.intInstrucaoInvalida; break;
                    }
                }

                if (irpt != Interrupts.noInterrupt) {
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
            cpu = new CPU(mem, false); // debug inicia desligado
        }
    }

    // ======================= S O ==========================================
    public class InterruptHandling {
        private HW hw;
        public InterruptHandling(HW _hw) { hw = _hw; }
        public void handle(Interrupts irpt) {
            System.out.println("                                               Interrupcao " + irpt);
        }
    }

    public class SysCallHandling {
        private HW hw;
        public SysCallHandling(HW _hw) { hw = _hw; }
        public void stop() { System.out.println("                                               SYSCALL STOP"); }
        public void handle() {
            System.out.println("SYSCALL pars:  " + hw.cpu.reg[8] + " / " + hw.cpu.reg[9]);
            if (hw.cpu.reg[8]==1){
                // entrada
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
        int[] aloca(int nroPalavras);
        void desaloca(int[] tabelaPaginas);
    }

    public class SimplePagingGM implements GM {
        private final int tamPg;
        private final int qtdFrames;
        private final boolean[] livre;

        public SimplePagingGM(int tamMemPalavras, int tamPgPalavras) {
            this.tamPg = tamPgPalavras;
            this.qtdFrames = tamMemPalavras / tamPg;
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
            if (frames.size() < paginasNec) return null;
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
        
        // MODIFICADO: agora é público para ser usado pelo GP
        public void loadProgramPaged(Word[] prog, int[] tabelaPaginas) {
            Word[] m = hw.mem.pos;
            for (int i = 0; i < prog.length; i++) {
                int pag = i / TAM_PG;
                int off = i % TAM_PG;
                int frame = tabelaPaginas[pag];
                int base = frame * TAM_PG;
                int fis = base + off;
                m[fis] = new Word(prog[i].opc, prog[i].ra, prog[i].rb, prog[i].p);
            }
        }

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
    }

    // ======================= SO ==========================================
    // ESTA CLASSE FOI AMPLAMENTE MODIFICADA PARA INCLUIR O GERENTE DE PROCESSOS
    public class SO {
        public InterruptHandling ih;
        public SysCallHandling sc;
        public Utilities utils;
        public GM gm;
        public ProcessManager gp; // NOVO: Gerente de Processos
        private HW hw; // ADICIONE ESTA LINHA

        public SO(HW hw) {
            this.hw = hw; // ADICIONE ESTA LINHA
            ih = new InterruptHandling(hw);
            sc = new SysCallHandling(hw);
            hw.cpu.setAddressOfHandlers(ih, sc);
            gm = new SimplePagingGM(hw.mem.pos.length, TAM_PG);
            utils = new Utilities(hw, gm);
            gp = new ProcessManager(hw); // PASSE O HW PARA O GP
        }
        
        // NOVO: Enum para estados do processo
        public enum ProcessState { READY, RUNNING, TERMINATED }

        // NOVO: Classe para o Process Control Block (PCB)
        public class PCB {
            private final int id;
            private int pc;
            private final int[] pageTable;
            private ProcessState state;

            public PCB(int id, int[] pageTable) {
                this.id = id;
                this.pc = 0; // PC inicial sempre 0
                this.pageTable = pageTable;
                this.state = ProcessState.READY;
            }
            public int getId() { return id; }
            public int getPc() { return pc; }
            public void setPc(int pc) { this.pc = pc; }
            public int[] getPageTable() { return pageTable; }
            public ProcessState getState() { return state; }
            public void setState(ProcessState state) { this.state = state; }
        }

        // NOVO: Classe para o Gerente de Processos (GP)
        public class ProcessManager {
            private HW hw; // ADICIONE ESTA LINHA
            private List<PCB> pcbList;
            private Queue<PCB> readyQueue;
            private PCB runningProcess;
            private int nextProcessId;

            public ProcessManager(HW hw) { // ALTERE O CONSTRUTOR
                this.hw = hw; // ADICIONE ESTA LINHA
                pcbList = new ArrayList<>();
                readyQueue = new LinkedList<>();
                runningProcess = null;
                nextProcessId = 1;
            }
            
            // Funcionalidade: cria um novo processo
            public int criaProcesso(Word[] programa) {
                if (programa == null) {
                    System.out.println("Erro: Programa não encontrado.");
                    return -1;
                }
                int[] tabelaPaginas = gm.aloca(programa.length);
                if (tabelaPaginas == null) {
                    System.out.println("Erro: Falha de alocação de memória.");
                    return -1;
                }

                PCB pcb = new PCB(nextProcessId++, tabelaPaginas);
                utils.loadProgramPaged(programa, pcb.getPageTable());
                
                pcbList.add(pcb);
                readyQueue.add(pcb);
                
                System.out.println("Processo " + pcb.getId() + " criado e adicionado à fila de prontos.");
                return pcb.getId();
            }
            
            // Funcionalidade: desaloca um processo
            public void desalocaProcesso(int id) {
                PCB pcb = findPcbById(id);
                if (pcb == null) {
                    System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                    return;
                }
                
                gm.desaloca(pcb.getPageTable());
                pcbList.remove(pcb);
                readyQueue.remove(pcb);
                if (runningProcess != null && runningProcess.getId() == id) {
                    runningProcess = null;
                }
                
                System.out.println("Processo " + id + " desalocado.");
            }
            
            public void executaProcesso(int id) {
                PCB pcb = findPcbById(id);
                if (pcb == null) {
                    System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                    return;
                }
                if (pcb.getState() == ProcessState.TERMINATED) {
                    System.out.println("Erro: Processo " + id + " já foi terminado.");
                    return;
                }
                
                runningProcess = pcb;
                readyQueue.remove(pcb);
                pcb.setState(ProcessState.RUNNING);

                System.out.println("---------------------------------- Inicia execução do processo " + id);
                hw.cpu.setMMU(pcb.getPageTable(), TAM_PG);
                hw.cpu.setContext(pcb.getPc());
                hw.cpu.run(); // Executa até um STOP ou interrupção
                pcb.setPc(hw.cpu.getContext()); // Salva o PC
                System.out.println("---------------------------------- Fim execução do processo " + id);

                // Em um SO simples como este, o processo que para é considerado terminado
                pcb.setState(ProcessState.TERMINATED);
                runningProcess = null;
            }

            public void listAllProcesses() {
                System.out.println("Lista de todos os processos:");
                if (pcbList.isEmpty()) {
                    System.out.println("Nenhum processo no sistema.");
                    return;
                }
                for (PCB pcb : pcbList) {
                    System.out.println("  ID: " + pcb.getId() + 
                                       ", Estado: " + pcb.getState() + 
                                       ", PC: " + pcb.getPc() + 
                                       ", Tabela de Páginas: " + Arrays.toString(pcb.getPageTable()));
                }
            }
            
            public void dumpProcess(int id) {
                PCB pcb = findPcbById(id);
                if (pcb == null) {
                    System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                    return;
                }
                System.out.println("--- Dump do Processo ID: " + pcb.getId() + " ---");
                System.out.println("  Estado: " + pcb.getState());
                System.out.println("  PC Lógico: " + pcb.getPc());
                System.out.println("  Tabela de Páginas: " + Arrays.toString(pcb.getPageTable()));
                System.out.println("  Conteúdo da Memória (visão física):");
                for (int frame : pcb.getPageTable()) {
                    int start = frame * TAM_PG;
                    int end = start + TAM_PG;
                    System.out.println("    Frame " + frame + " (Endereços Físicos " + start + "-" + (end-1) + "):");
                    utils.dump(start, end);
                }
                System.out.println("--- Fim do Dump ---");
            }
            
            private PCB findPcbById(int id) {
                for (PCB pcb : pcbList) {
                    if (pcb.getId() == id) {
                        return pcb;
                    }
                }
                return null;
            }
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
    
    // MÉTODO RUN MODIFICADO PARA SER O SHELL INTERATIVO
    public void run() {
        System.out.println("Sistema Operacional iniciado. Digite 'help' para ver os comandos.");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String[] command = scanner.nextLine().split(" ");
            
            switch (command[0].toLowerCase()) {
                case "new":
                    if (command.length > 1) {
                        Word[] prog = progs.retrieveProgram(command[1]);
                        so.gp.criaProcesso(prog);
                    } else {
                        System.out.println("Uso: new <nomeDoPrograma>");
                    }
                    break;
                case "rm":
                    if (command.length > 1) {
                        try {
                            so.gp.desalocaProcesso(Integer.parseInt(command[1]));
                        } catch (NumberFormatException e) {
                            System.out.println("ID inválido. Deve ser um número.");
                        }
                    } else {
                        System.out.println("Uso: rm <id>");
                    }
                    break;
                case "ps":
                    so.gp.listAllProcesses();
                    break;
                case "dump":
                     if (command.length > 1) {
                        try {
                            so.gp.dumpProcess(Integer.parseInt(command[1]));
                        } catch (NumberFormatException e) {
                            System.out.println("ID inválido. Deve ser um número.");
                        }
                    } else {
                        System.out.println("Uso: dump <id>");
                    }
                    break;
                case "dumpm":
                    if (command.length > 2) {
                         try {
                            int start = Integer.parseInt(command[1]);
                            int end = Integer.parseInt(command[2]);
                            so.utils.dump(start, end);
                        } catch (NumberFormatException e) {
                            System.out.println("Endereços inválidos. Devem ser números.");
                        }
                    } else {
                        System.out.println("Uso: dumpM <inicio> <fim>");
                    }
                    break;
                case "exec":
                     if (command.length > 1) {
                        try {
                            so.gp.executaProcesso(Integer.parseInt(command[1]));
                        } catch (NumberFormatException e) {
                            System.out.println("ID inválido. Deve ser um número.");
                        }
                    } else {
                        System.out.println("Uso: exec <id>");
                    }
                    break;
                case "traceon":
                    hw.cpu.setDebug(true);
                    System.out.println("Modo trace ativado.");
                    break;
                case "traceoff":
                    hw.cpu.setDebug(false);
                    System.out.println("Modo trace desativado.");
                    break;
                case "exit":
                    System.out.println("Saindo do sistema...");
                    scanner.close();
                    return;
                case "help":
                    System.out.println("Comandos disponíveis:");
                    System.out.println("  new <nome>     - Cria um novo processo (ex: new fatorial)");
                    System.out.println("  rm <id>        - Remove um processo do sistema");
                    System.out.println("  ps             - Lista todos os processos");
                    System.out.println("  dump <id>      - Mostra informações e memória de um processo");
                    System.out.println("  dumpM <ini> <fim> - Mostra conteúdo da memória física");
                    System.out.println("  exec <id>      - Executa um processo");
                    System.out.println("  traceOn        - Liga o modo de debug da CPU");
                    System.out.println("  traceOff       - Desliga o modo de debug da CPU");
                    System.out.println("  exit           - Encerra o sistema");
                    break;
                default:
                    System.out.println("Comando desconhecido: " + command[0]);
            }
        }
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