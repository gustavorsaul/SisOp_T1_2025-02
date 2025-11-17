import java.util.Arrays;

// Interface para o Gerente de Memória.
    interface GM_Interface {
    int alocaFrameLivre();
    void desalocaFrames(SisOp_ProcessManager.PageTableEntry[] tabelaPaginas);
    void liberaFrame(int frameNumber);
    int findVictimFrame();
    }

    public class SisOp_GM implements GM_Interface {
    private int tamPg;
    private int qtdFrames;
    private boolean[] livre;
    private int lastVictimPointer = 0; // Ponteiro para política "Relógio" (FIFO Circular)

    public SisOp_GM(int tamMem, int tamPg) {
        this.tamPg = tamPg;
        this.qtdFrames = tamMem / tamPg;
        this.livre = new boolean[qtdFrames];
        Arrays.fill(livre, true);
    }

    @Override
    public int alocaFrameLivre() {
        for (int f = 0; f < qtdFrames; f++) {
            if (livre[f]) {
                livre[f] = false;
                return f; // Retorna o primeiro frame livre encontrado
            }
        }
        System.out.println("GM: Memória cheia. Nenhum frame livre encontrado.");
        return -1; 
    }

    @Override
    public void liberaFrame(int frameNumber) {
        if (frameNumber >= 0 && frameNumber < livre.length) {
            livre[frameNumber] = true;
        } else {
            System.out.println("GM AVISO: Tentativa de liberar frame inválido: " + frameNumber);
        }
    }

    @Override
    public void desalocaFrames(SisOp_ProcessManager.PageTableEntry[] tabelaPaginas) {
        if (tabelaPaginas == null) return;
        
        System.out.print("GM: Desalocando frames... ");
        for (SisOp_ProcessManager.PageTableEntry pte : tabelaPaginas) {
            if (pte.valid) { // Só libera frames que estão realmente em uso
                int f = pte.frameNumber;
                if (f >= 0 && f < livre.length) {
                    livre[f] = true;
                    System.out.print(f + " ");
                    
                    pte.valid = false;
                    pte.frameNumber = -1;
                    pte.dirty = false;
                }
            }
        }
        System.out.println();
    }

    @Override
    public int findVictimFrame() {
        
        int victimFrame = lastVictimPointer;
        lastVictimPointer = (lastVictimPointer + 1) % qtdFrames;
        
        System.out.println("GM: Vitimização! Frame " + victimFrame + " foi escolhido.");
        
        return victimFrame;
    }
}