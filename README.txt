================================
README - Simulador de SO
================================

--------------------------------
1. Nomes dos Integrantes
--------------------------------

- [Nome do Integrante 1]
- [Nome do Integrante 2]
- [etc.]

--------------------------------
2. Implementação
--------------------------------

Este programa implementa todas as funcionalidades pedidas nos trabalhos 
    T2a (E/S Concorrente) e 
    T2b (Memória Virtual), 
    além do Logger.

O que foi feito:
    * O sistema usa threads para o Shell, CPU, Console e Disco rodarem ao mesmo tempo.
    * Memória Virtual: os programas só carregam a primeira página na memória, 
        o resto é carregado quando o programa precisa (Page Fault).
    * Substituição de página (Vitimização) quando a memória fica cheia (política FIFO simples).
    * Entrada e Saída (como pedir um número) rodam em paralelo, bloqueando só o processo que pediu, não o sistema todo.
    * Um arquivo de log (na pasta 'logs') é criado a cada execução, registrando tudo o que acontece.
    * Os resultados dos programas ficam na memória mesmo depois que eles terminam, 
        para que possam ser vistos com o comando 'dumpm'.

Restrições:
    * Nenhuma restrição conhecida. O sistema está funcionando como esperado.

--------------------------------
3. Testes
--------------------------------

Para testar, a forma mais fácil é:
1.  Iniciar o programa.
2.  Digitar: "thread2" (para ligar o escalonador automático)
3.  Digitar: "new nomeDoPrograma" (para criar um processo)

!ATENÇÃO!
    É possível que em algumas etapas do programa o terminal fique vazio esperando uma entrada do teclado.
    Neste caso, tente apertar a tecla ENTER para dar continuidade ao programa e forçar a próxima instrução.
    Isso provavelmente está ocorrendo devido às várias Thread rodando em simultâneo e realizando prints e instruções,
    podendo causar alguma "sobra" no terminal, sendo necessário apertar ENTER nestes casos e aparecer o próximo print.

---
Teste: fatorial
    * Descrição:** Calcula o fatorial de 5 e imprime 120 no console.
    * Como Executar:
        Digite: "thread2"
        Digite: "new fatorial"
    * Resultado Esperado:
        O console vai mostrar "Dispositivo de E/S ...: 120" e depois "Processo ... terminou."

---
Teste: fatorialV2
    * Descrição: Igual ao fatorial, mas o programa é maior, o que força um Page Fault.
    * Como Executar:
        Digite: "thread2"
        Digite: "new fatorialV2"
    * Resultado Esperado:
        O log (na pasta 'logs') vai registrar um "Page_Fault". 
        O console vai mostrar "Dispositivo de E/S ...: 120" e depois "Processo ... terminou."

---
Teste: progMinimo
    * Descrição:** Apenas escreve 999 em algumas posições de memória (8 a 12) e para.
    * Como Executar:
        Digite: "thread2"
        Digite: "new progMinimo"
    * Resultado Esperado:
        O processo vai terminar rapidamente.
    * Verificação:
        Digite "dumpm 8 13". 
        Você deve ver o número 999 escrito nas posições 8, 9, 10, 11 e 12.

---
Teste: fibonacci10 (ou fibonacci10v2)
    * Descrição:
        Calcula os 10 primeiros números de Fibonacci (0 a 34) e 
        salva na memória (endereços lógicos 20 a 29). Não imprime no console.
    * Como Executar:
        Digite: "thread2"
        Digite: "new fibonacci10" ou "new fibonacci10v2"
    * Resultado Esperado:
        O log vai registrar Page Faults para carregar as páginas onde os dados são salvos. O processo terminará.
    * Verificação:
        Digite "dumpm 0 100" e procure pelos números 0, 1, 1, 2, 3, 5, 8, 13, 21, 34. 
        Eles estarão juntos em algum frame de memória.

---
Teste: fibonacciREAD
    * Descrição: ]
        Teste principal de funcionamento das implementações no programa. 
        Pede um número 'N' ao usuário, calcula fib(N) e salva a sequência na memória (a partir do endereço 41).
    * Como Executar:
        Digite: "thread2"
        Digite: "new fibonacciREAD"
        Espere o prompt: "Dispositivo de E/S: ... Digite um número:"
            !ATENÇÃO! 
                Repetindo a instrução de ATENÇÃO anterior aos testes.
                É possível que quando o programa seja bloqueado para E/S, não apareça a mensagem acima.
                Portanto, tente apertar ENTER para dar continuidade ao código e forçar a execução da instrução.
                Isso provavelmente está ocorrendo devido às várias Thread rodando em simultâneo e realizando prints,
                podendo causar alguma "sobra" no terminal, sendo necessário apertar ENTER nestes casos.
        Digite "7" ou outro número e pressione Enter.
    * Resultado Esperado:
        O log mostrará Page Faults (para carregar a página da E/S) e bloqueios por E/S. O processo terminará.
    * Verificação:
        Digite "dumpm 0 100". Você verá os resultados espalhados pela memória física:
            O número "7" (seu input) estará em um frame.
            A sequência "0, 1, 1, 2, 3, 5, 8" estará em outro frame.
            O resultado final "13" (fib(7)) estará em um terceiro frame.

---
Teste: PB
    * Descrição:
        Calcula o fatorial de 7 (5040) e salva na memória no endereço 15. Não imprime.
    * Como Executar:
        Digite: "thread2"
        Digite: "new PB"
    * Resultado Esperado: 
        O processo terminará.
    * Verificação:
        Digite "dumpm 0 20". Você deve ver o número "5040" na posição 15.

---
Teste: PC
    * Descrição:
        Ordena um vetor de números (4, 3, 5, 1, 2) que está na memória (endereços 46-50) usando Bubble Sort.
    * Como Executar:
        Digite: "thread2"
        Digite: "new PC"
    * Resultado Esperado:
        O log registrará vários Page Faults para carregar o código e os dados. O processo terminará.
    * Verificação:
        Digite "dumpM 0 100" e procure pelos dados. Você verá os números ordenados: "1, 2, 3, 4, 5".
