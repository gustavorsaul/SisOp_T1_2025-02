import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator; 
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

// Gerencia todos os aspectos dos processos.
public class SisOp_ProcessManager {

    // Estrutura de uma Entrada na Tabela de Paginas
    public static class PageTableEntry {
        public int frameNumber = -1;
        public boolean valid = false;
        public boolean dirty = false;
        public int diskAddress = -1;
    }

    // Classe 'container' para retornar informações sobre a vítima
    public class VictimInfo {
        public PCB pcbOwner;        
        public int pageNumber;      
        public PageTableEntry pte;  

        public VictimInfo(PCB pcb, int pageNum, PageTableEntry pte) {
            this.pcbOwner = pcb;
            this.pageNumber = pageNum;
            this.pte = pte;
        }
    }

    public enum ProcessState {
        READY, RUNNING, BLOCKED,TERMINATED
    }

    // Process Control Block
    public static class PCB {
        private int id;
        private int pc;
        private PageTableEntry[] pageTable;
        private int[] registradores;
        private ProcessState state;
        private String programName;

       public PCB(int id, int numPaginas, String programName) {
            this.id = id;
            this.pc = 0;
            this.state = ProcessState.READY;
            this.registradores = new int[10];
            this.programName = programName;

            this.pageTable = new PageTableEntry[numPaginas];
            for (int i = 0; i < numPaginas; i++) {
                this.pageTable[i] = new PageTableEntry();
            }
        }
        
        public String getProgramName() { return this.programName; }
        public int getId() { return id; }
        public void setContext(int pc, int[] regs) { this.pc = pc; this.registradores = Arrays.copyOf(regs, regs.length); }
        public int getPc() { return pc; }
        public PageTableEntry[] getPageTable() { return pageTable; }
        public int[] getRegistradores() { return registradores; }
        public ProcessState getState() { return state; }
        public void setState(ProcessState state) { this.state = state; }
    }

    private List<PCB> pcbList;
    private Queue<PCB> readyQueue;
    private Queue<PCB> blockedQueue;     // Fila de espera do Console
    private Queue<PCB> blockedVMQueue;   // Fila de espera do Disco (VM)
    private PCB runningProcess;
    private int nextProcessId;
    private final Object schedulerLock = new Object();

    private SisOp so;

    public SisOp_ProcessManager(SisOp so) {
        this.so = so;
        this.pcbList = new ArrayList<>();
        this.readyQueue = new LinkedList<>();
        this.blockedQueue = new LinkedList<>();
        this.blockedVMQueue = new LinkedList<>();
        this.runningProcess = null;
        this.nextProcessId = 1;
    }

    public PCB getRunningProcess() { return runningProcess; }
    public Queue<PCB> getReadyQueue() { return readyQueue; }
    public Object getSchedulerLock() { return schedulerLock; }

    public void execAllBlocking(int quantum) {
        if (so.getMode() == SisOp.ExecutionMode.THREADED) {
            System.out.println("Comando 'execAll' não está disponível no modo de execução contínua (threaded).");
            return;
        }
        if (readyQueue.isEmpty()) {
            System.out.println("Nenhum processo na fila de prontos para executar.");
            return;
        }
        System.out.println("---------------------------------- Iniciando execução BLOQUEANTE de processos");
        escalonar(false);
        while (runningProcess != null) {
            so.hw.cpu.step(quantum);
        }
        System.out.println("---------------------------------- Todos os processos terminaram (modo bloqueante).");
    }

    public int criaProcesso(Hardware.Word[] programa, String programName) {
        synchronized (schedulerLock) {
        if (programa == null) {
                    System.out.println("Erro: Programa não encontrado.");
                    return -1;
                }

        int numPaginas = (programa.length + so.TAM_PAG - 1) / so.TAM_PAG;
            if (numPaginas == 0) numPaginas = 1;

        PCB pcb = new PCB(nextProcessId++, numPaginas, programName);;

        int frameParaPagina0 = so.gm.alocaFrameLivre();

        if (frameParaPagina0 < 0) {
            System.out.println("Erro: Falha de alocação de memória (sem frames livres para página 0).");
            return -1; 
        }

        pcb.getPageTable()[0].frameNumber = frameParaPagina0;
        pcb.getPageTable()[0].valid = true;
        pcb.getPageTable()[0].dirty = false;

        so.utils.loadPage(programa, 0, frameParaPagina0, so.TAM_PAG);

        pcbList.add(pcb);
        readyQueue.add(pcb);
        System.out.println("Processo " + pcb.getId() + " criado. (Página 0 carregada no Frame " + frameParaPagina0 + ")");

        if (so.getMode() == SisOp.ExecutionMode.THREADED) {
            schedulerLock.notifyAll();
        }
        return pcb.getId();
        }
    }

    public void bloqueiaProcessoAtual() {
        synchronized (schedulerLock) {
            if (runningProcess == null) return;

            System.out.println("Processo " + runningProcess.getId() + " BLOQUEADO esperando I/O.");
            runningProcess.setContext(so.hw.cpu.getContextPC(), so.hw.cpu.getContextRegs());
            runningProcess.setState(ProcessState.BLOCKED);

            blockedQueue.add(runningProcess); 
            runningProcess = null;
            escalonar(true); 
        }
    }

    public void bloqueiaProcessoVM() {
        synchronized (schedulerLock) {
            if (runningProcess == null) return;

            System.out.println("Processo " + runningProcess.getId() + " BLOQUEADO esperando DISCO-VM.");
            runningProcess.setContext(so.hw.cpu.getContextPC(), so.hw.cpu.getContextRegs());
            runningProcess.setState(ProcessState.BLOCKED);

            blockedVMQueue.add(runningProcess); 
            runningProcess = null;
            escalonar(true); 
        }
    }

    public void desbloqueiaProcesso(int id) {
        synchronized (schedulerLock) {
            PCB pcb = null;
            Iterator<PCB> it = blockedQueue.iterator();
            while (it.hasNext()) {
                PCB p = it.next();
                if (p.getId() == id) {
                    pcb = p;
                    it.remove(); 
                    break;
                }
            }

            if (pcb == null) {
                System.out.println("AVISO: ProcessManager não encontrou P" + id + " na fila de bloqueados para desbloquear.");
                return;
            }
            
            pcb.setState(ProcessState.READY);
            readyQueue.add(pcb); 

            System.out.println("Processo " + pcb.getId() + " DESBLOQUEADO e movido para Prontos.");

            if (so.getMode() == SisOp.ExecutionMode.THREADED && runningProcess == null) {
                schedulerLock.notifyAll();
            }
        }
    }
    
    public void desbloqueiaProcessoVM(int id) {
        synchronized (schedulerLock) {
            PCB pcb = null;
            Iterator<PCB> it = blockedVMQueue.iterator(); 
            while (it.hasNext()) {
                PCB p = it.next();
                if (p.getId() == id) {
                    pcb = p;
                    it.remove(); 
                    break;
                }
            }

            if (pcb == null) {
                System.out.println("AVISO: PM.desbloqueiaProcessoVM não encontrou P" + id + " na fila (VM).");
                return;
            }
            
            pcb.setState(ProcessState.READY);
            readyQueue.add(pcb);

            System.out.println("Processo " + pcb.getId() + " DESBLOQUEADO (VM) e movido para Prontos.");

            if (so.getMode() == SisOp.ExecutionMode.THREADED && runningProcess == null) {
                schedulerLock.notifyAll();
            }
        }
    }

    public void desalocaProcesso(int id) {
        synchronized (schedulerLock) {
            PCB pcb = findPcbById(id);
            if (pcb == null) {
                System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                return;
            }
            
            so.gm.desalocaFrames(pcb.getPageTable());
            pcbList.remove(pcb);
            readyQueue.remove(pcb);
            blockedQueue.remove(pcb);
            blockedVMQueue.remove(pcb);

            if (runningProcess != null && runningProcess.getId() == id) {
                System.out.println("Processo " + id + " desalocado (estava rodando).");
                terminaProcessoAtual(); 
            } else {
                 System.out.println("Processo " + id + " desalocado.");
            }
        }
    }

    public PCB findPcbById(int id) {
        for (PCB pcb : pcbList)
            if (pcb.getId() == id)
                return pcb;
        return null;
    }

    public VictimInfo findAndInvalidateVictim(int frameNumber) {
        synchronized (schedulerLock) {
            for (PCB pcb : pcbList) {
                if (pcb.getPageTable() != null) {
                    for (int i = 0; i < pcb.getPageTable().length; i++) {
                        PageTableEntry pte = pcb.getPageTable()[i];
                        if (pte.valid && pte.frameNumber == frameNumber) {
                            
                            pte.valid = false;
                            VictimInfo vitima = new VictimInfo(pcb, i, pte);
                            
                            System.out.println("                                               PM: Vítima encontrada! P-" + pcb.getId() + ", Pág: " + i + ", Frame: " + frameNumber);
                            
                            return vitima;
                        }
                    }
                }
            }
        }
        return null; 
    }
    
    public void escalonar(boolean processoTerminou) {
        synchronized (schedulerLock) {
            if (runningProcess != null && !processoTerminou) {
                runningProcess.setContext(so.hw.cpu.getContextPC(), so.hw.cpu.getContextRegs());
                runningProcess.setState(ProcessState.READY);
                readyQueue.add(runningProcess);
                System.out.println("Processo " + runningProcess.getId() + " salvo e movido para a fila de prontos.");
            }
            if (readyQueue.isEmpty()) {
                runningProcess = null;
                so.hw.cpu.stop();
                if (so.getMode() == SisOp.ExecutionMode.BLOCKING) {
                    System.out.println("---------------------------------- Fila de prontos vazia. Fim do 'execAll'.");
                } else {
                    System.out.println("---------------------------------- Fila de prontos vazia. CPU em espera.");
                }
                return;
            }
            runningProcess = readyQueue.poll();
            runningProcess.setState(ProcessState.RUNNING);
            so.hw.cpu.setContext(runningProcess.getPc(), runningProcess.getRegistradores());
            so.hw.cpu.setMMU(runningProcess.getPageTable());
            so.hw.cpu.resetInstructionCounter();
            so.hw.cpu.start();
            System.out.println(">>> Assumindo CPU: Processo " + runningProcess.getId());
        }
    }

    public void terminaProcessoAtual() {
        synchronized (schedulerLock) {
            if (runningProcess == null)
                return;
            System.out.println("Processo " + runningProcess.getId() + " terminou.");
            runningProcess.setState(ProcessState.TERMINATED);
            so.gm.desalocaFrames(runningProcess.getPageTable()); 
            pcbList.remove(runningProcess);
            runningProcess = null;
            escalonar(true);
        }
    }
    public void listAllProcesses() {
        synchronized (schedulerLock) {
            System.out.println("Lista de todos os processos:");
            if (pcbList.isEmpty()) {
                System.out.println("Nenhum processo no sistema.");
                return;
            }
            for (PCB pcb : pcbList) {
                System.out.println("  ID: " + pcb.getId() + ", Estado: " + pcb.getState() + ", PC: " + pcb.getPc());
            }
            System.out.println("--- Fila de Prontos: " + readyQueue.stream().map(PCB::getId).toList());
            System.out.println("--- Fila de Bloqueados (Console): " + blockedQueue.stream().map(PCB::getId).toList());
            System.out.println("--- Fila de Bloqueados (VM): " + blockedVMQueue.stream().map(PCB::getId).toList()); 
        }
    }

    public void dumpProcess(int id) {
        synchronized (schedulerLock) {
            PCB pcb = findPcbById(id);
            if (pcb == null) {
                System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                return;
            }
            System.out.println("--- Dump do Processo ID: " + pcb.getId() + " ---");
            System.out.println("  Estado: " + pcb.getState() + ", PC Lógico: " + pcb.getPc());
            
            System.out.println("  Tabela de Páginas (" + pcb.getPageTable().length + " entradas):");
            for (int i=0; i < pcb.getPageTable().length; i++) {
                PageTableEntry pte = pcb.getPageTable()[i];
                if (pte.valid) {
                    System.out.printf("    Pag %2d -> Frame %2d (Valid: %b, Dirty: %b)\n", i, pte.frameNumber, pte.valid, pte.dirty);
                } else {
                    System.out.printf("    Pag %2d -> [No Disco] (Valid: %b)\n", i, pte.valid);
                }
            }
            
            System.out.println("  Conteúdo da Memória (visão física dos frames válidos):");
            for (PageTableEntry pte : pcb.getPageTable()) {
                if (pte.valid) {
                    int frame = pte.frameNumber;
                    int start = frame * so.TAM_PAG;
                    int end = start + so.TAM_PAG;
                    System.out.println("    Frame " + frame + " (Endereços Físicos " + start + "-" + (end - 1) + "):");
                    so.utils.dump(start, end);
                }
            }
            System.out.println("--- Fim do Dump ---");
        }
    }
}