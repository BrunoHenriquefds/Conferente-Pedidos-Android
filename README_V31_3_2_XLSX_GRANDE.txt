V31.3.2 - correção de crash ao importar planilhas XLSX grandes.
O leitor deixou de montar sharedStrings.xml e sheet1.xml inteiros em DOM.
Agora usa SAX streaming, reduzindo drasticamente o consumo de memória no Android.
Testado conceitualmente para planilhas grandes como estoque com dezenas de milhares de linhas.
