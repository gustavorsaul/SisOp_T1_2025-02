import java.util.Arrays;

// Interface para o Gerente de Memória.
interface GM_Interface {
    // Métodos antigos removidos/alterados
}

// Implementação com gerenciamento de frames para VM
public class SisOp_GM implements GM_Interface {
    private int tamPg;
    private int qtdFrames;
    private FrameInfo[] frameMap; // Mapa de frames físicos

    // --- MUDANÇA (VITIMIZAÇÃO) ---
    public class FrameInfo {
        public SisOp_ProcessManager.PCB pcb;
        public int pageNumber; // Página lógica do processo
        public SisOp_ProcessManager.PCB waiter; // Processo esperando este frame
        public int waiterPage = -1; // Página que o waiter está esperando

        public FrameInfo(SisOp_ProcessManager.PCB pcb, int pageNumber) {
            this.pcb = pcb;
            this.pageNumber = pageNumber;
            this.waiter = null;
        }
    }

    public SisOp_GM(int tamMem, int tamPg) {
        this.tamPg = tamPg;
        this.qtdFrames = tamMem / tamPg;
        this.frameMap = new FrameInfo[qtdFrames];
    }

    public Hardware.PageTableEntry[] createPageTable(int nroPalavras) {
        int paginasNec = (nroPalavras + this.tamPg - 1) / this.tamPg;
        if (paginasNec == 0) paginasNec = 1; 
        
        Hardware.PageTableEntry[] table = new Hardware.PageTableEntry[paginasNec];
        for (int i = 0; i < paginasNec; i++) {
            table[i] = new Hardware.PageTableEntry();
        }
        return table;
    }

    public void desaloca(SisOp_ProcessManager.PCB pcb) {
        if (pcb == null) return;
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] != null && frameMap[i].pcb.getId() == pcb.getId()) {
                frameMap[i] = null; // Libera o frame
            }
        }
    }
    
    public int findFreeFrame() {
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] == null) {
                return i;
            }
        }
        return -1; // Nenhum frame livre
    }

    public int selectVictimFrame() {
        // Política de vitimização simples: FIFO (pega o primeiro ocupado que não esteja esperando)
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] != null && frameMap[i].waiter == null) {
                return i; // Encontrou uma vítima
            }
        }
        
        return 0; // Fallback (não deve acontecer se houver > 1 frame)
    }

    public FrameInfo getFrameInfo(int frame) {
        if (frame < 0 || frame >= qtdFrames) return null;
        return frameMap[frame];
    }
    
    public void occupyFrame(int frame, SisOp_ProcessManager.PCB pcb, int page) {
        if (frame < 0 || frame >= qtdFrames) return;
        frameMap[frame] = new FrameInfo(pcb, page);
    }
    
    public void freeFrame(int frame) {
        if (frame < 0 || frame >= qtdFrames) return;
        frameMap[frame] = null;
    }
    
    // --- MUDANÇA (VITIMIZAÇÃO) ---
    // Agora armazena qual página o waiter precisa
    public void setWaiter(int frame, SisOp_ProcessManager.PCB waiterPcb, int pageNeeded) {
        if (frame < 0 || frame >= qtdFrames) return;
        if (frameMap[frame] != null) {
            frameMap[frame].waiter = waiterPcb;
            frameMap[frame].waiterPage = pageNeeded;
        }
    }
}