import java.util.Arrays;

interface GM_Interface {
}

// Gerenciador de Memória: controla frames e tabelas de páginas
public class SisOp_GM implements GM_Interface {
    private int tamPg;
    private int qtdFrames;
    private FrameInfo[] frameMap;

    // Informações sobre o que está ocupando um frame
    public class FrameInfo {
        public SisOp_ProcessManager.PCB pcb;
        public int pageNumber;
        public SisOp_ProcessManager.PCB waiter;
        public int waiterPage = -1; 

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

    // Cria tabela de páginas para um programa
    public Hardware.PageTableEntry[] createPageTable(int nroPalavras) {
        int paginasNec = (nroPalavras + this.tamPg - 1) / this.tamPg;
        if (paginasNec == 0) paginasNec = 1; 
        
        Hardware.PageTableEntry[] table = new Hardware.PageTableEntry[paginasNec];
        for (int i = 0; i < paginasNec; i++) {
            table[i] = new Hardware.PageTableEntry();
        }
        return table;
    }

    // Libera todos os frames de um processo
    public void desaloca(SisOp_ProcessManager.PCB pcb) {
        if (pcb == null) return;
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] != null && frameMap[i].pcb.getId() == pcb.getId()) {
                frameMap[i] = null; 
            }
        }
    }
    
    // Busca um frame livre na memória
    public int findFreeFrame() {
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] == null) {
                return i;
            }
        }
        return -1; 
    }

    // Seleciona um frame para ser vitimado (substituição)
    public int selectVictimFrame() {
        for (int i = 0; i < qtdFrames; i++) {
            if (frameMap[i] != null && frameMap[i].waiter == null) {
                return i; 
            }
        }
        
        return 0; 
    }

    // Retorna informações sobre um frame específico
    public FrameInfo getFrameInfo(int frame) {
        if (frame < 0 || frame >= qtdFrames) return null;
        return frameMap[frame];
    }
    
    // Marca um frame como ocupado por uma página
    public void occupyFrame(int frame, SisOp_ProcessManager.PCB pcb, int page) {
        if (frame < 0 || frame >= qtdFrames) return;
        frameMap[frame] = new FrameInfo(pcb, page);
    }
    
    // Libera um frame específico
    public void freeFrame(int frame) {
        if (frame < 0 || frame >= qtdFrames) return;
        frameMap[frame] = null;
    }
    
    // Define processo esperando por este frame (durante swap)
    public void setWaiter(int frame, SisOp_ProcessManager.PCB waiterPcb, int pageNeeded) {
        if (frame < 0 || frame >= qtdFrames) return;
        if (frameMap[frame] != null) {
            frameMap[frame].waiter = waiterPcb;
            frameMap[frame].waiterPage = pageNeeded;
        }
    }
}