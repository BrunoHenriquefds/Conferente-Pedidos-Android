CONFERENTE DE PEDIDOS ONLINE — ANDROID V1

Esta é a versão Android criada a partir do fluxo do Conferente desktop.

FUNÇÕES INCLUÍDAS
- Importação de relatórios XLSX do Mercado Livre e relatório de pedidos.
- Bipagem por ISBN/EAN/Código de barras/SKU usando leitor Bluetooth ou USB-C que funcione como teclado.
- Modo Conferência de Pacotes.
- Rastreamentos aceitos como equivalentes:
  MEL47640886859FMXDF01
  MEL47640886859
  47640886859FMXDF01
  47640886859
  Todos normalizam para 47640886859.
- Contadores de produtos: esperado, bipado e faltando.
- Contadores de pacotes: total, embalados e pendentes.
- Bloqueio de pacote já conferido e código não encontrado.
- Som OK, erro e vitória.
- Popup ao concluir todos os pacotes.
- Localização SS permanente.
- Importação de negativos.
- Reaproveitamento da quantidade já conferida em listas anteriores pelo pedido + código.
- Filtros TODOS/PENDENTES/PARCIAIS/FALTANDO/CONFERIDOS/NEGATIVOS.
- Pesquisa.
- Detalhes do item e rastreamento.
- Exportação XLSX somente com Código de Barras, Descrição, Quantidade Faltando e Localização SS; descrição com quebra de texto.
- SQLite local/offline.

IMPORTANTE
O projeto está pronto para gerar APK, mas o APK precisa ser compilado em um ambiente com Android SDK/NDK. Este ZIP inclui um workflow do GitHub Actions que faz isso automaticamente: envie o projeto a um repositório GitHub, abra Actions > Build Android APK > Run workflow e baixe o artefato gerado.

NO CELULAR
Para melhor velocidade, use leitor de código de barras Bluetooth/USB-C no modo HID/teclado. O cursor fica no campo de bipagem e ENTER finaliza a leitura.

ARQUIVOS
main.py                  interface Android/touch
app_core/importers.py    importação XLSX sem pandas
app_core/database.py     SQLite + conferência de produtos/pacotes
app_core/exporter.py     exportação XLSX
app_core/covers.py       base para capas online por ISBN
buildozer.spec           configuração do APK
.github/workflows/...    compilação automática no GitHub Actions
