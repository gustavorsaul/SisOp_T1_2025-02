import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

// Gerencia todos os aspectos dos processos.
public class SisOp_ProcessManager {

    public enum ProcessState {
        READY, RUNNING, TERMINATED
    }

    public static class PCB {
        private int id;
        private int pc;
        private int[] pageTable;
        private int[] registradores;
        private ProcessState state;

        public PCB(int id, int[] pageTable) {
            this.id = id;
            this.pc = 0;
            this.pageTable = pageTable;
            this.state = ProcessState.READY;
            this.registradores = new int[10];
        }

        public int getId() { return id; }
        public void setContext(int pc, int[] regs) { this.pc = pc; this.registradores = Arrays.copyOf(regs, regs.length); }
        public int getPc() { return pc; }
        public int[] getPageTable() { return pageTable; }
        public int[] getRegistradores() { return registradores; }
        public ProcessState getState() { return state; }
        public void setState(ProcessState state) { this.state = state; }
    }

    private List<PCB> pcbList;
    private Queue<PCB> readyQueue;
    private PCB runningProcess;
    private int nextProcessId;
    private final Object schedulerLock = new Object();

    private SisOp so;

    public SisOp_ProcessManager(SisOp so) {
        this.so = so;
        this.pcbList = new ArrayList<>();
        this.readyQueue = new LinkedList<>();
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

    public int criaProcesso(Hardware.Word[] programa) {
        synchronized (schedulerLock) {
            if (programa == null) {
                System.out.println("Erro: Programa não encontrado.");
                return -1;
            }
            int[] tabelaPaginas = so.gm.aloca(programa.length);
            if (tabelaPaginas == null) {
                System.out.println("Erro: Falha de alocação de memória.");
                return -1;
            }
            PCB pcb = new PCB(nextProcessId++, tabelaPaginas);
            so.utils.loadProgram(programa, pcb.getPageTable(), so.TAM_PAG);
            pcbList.add(pcb);
            readyQueue.add(pcb);
            System.out.println("Processo " + pcb.getId() + " criado e pronto.");
            if (so.getMode() == SisOp.ExecutionMode.THREADED) {
                schedulerLock.notifyAll();
            }
            return pcb.getId();
        }
    }

    public void desalocaProcesso(int id) {
        synchronized (schedulerLock) {
            PCB pcb = findPcbById(id);
            if (pcb == null) {
                System.out.println("Erro: Processo com ID " + id + " não encontrado.");
                return;
            }
            so.gm.desaloca(pcb.getPageTable());
            pcbList.remove(pcb);
            readyQueue.remove(pcb);
            if (runningProcess != null && runningProcess.getId() == id) {
                runningProcess = null;
                terminaProcessoAtual();
            }
            System.out.println("Processo " + id + " desalocado.");
        }
    }

    private PCB findPcbById(int id) {
        for (PCB pcb : pcbList)
            if (pcb.getId() == id)
                return pcb;
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
            so.gm.desaloca(runningProcess.getPageTable());
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
                System.out.println("  ID: " + pcb.getId() + ", Estado: " + pcb.getState() + ", PC: " + pcb.getPc() + ", Tabela: " + Arrays.toString(pcb.getPageTable()));
            }
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
            System.out.println("  Tabela de Páginas: " + Arrays.toString(pcb.getPageTable()));
            System.out.println("  Conteúdo da Memória (visão física):");
            for (int frame : pcb.getPageTable()) {
                int start = frame * so.TAM_PAG;
                int end = start + so.TAM_PAG;
                System.out.println("    Frame " + frame + " (Endereços Físicos " + start + "-" + (end - 1) + "):");
                so.utils.dump(start, end);
            }
            System.out.println("--- Fim do Dump ---");
        }
    }
}