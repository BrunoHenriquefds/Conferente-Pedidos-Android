package com.bruno.conferente;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.media.*;
import android.net.Uri;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.view.*;
import android.webkit.*;
import android.widget.*;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.*;
import org.w3c.dom.*;

import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.parsers.*;

public class MainActivity extends Activity {
    WebView web, printWeb;
    DB db;
    int batchId = 0;
    String mode = "items";
    int selectedId = 0;
    String selectedCoverUrl = "";
    boolean completionShown = false, packageCompletionShown = false;
    ToneGenerator tone;
    SharedPreferences prefs;

    static final int REPORT = 10, LOC = 11, NEG = 12, EXPORT = 13;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xff050b12);
        getWindow().setNavigationBarColor(0xff050b12);
        PDFBoxResourceLoader.init(getApplicationContext());
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        db = new DB(this);
        tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 95);
        web = new WebView(this);
        web.setBackgroundColor(0xff07111b);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                int last = db.latestBatchId();
                if (last > 0) {
                    batchId = last;
                    js("setBatch('" + esc(db.batchName(batchId)) + "')");
                }
                requestData();
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onDestroy() {
        if (tone != null) tone.release();
        if (db != null) db.close();
        super.onDestroy();
    }

    void js(String s) { runOnUiThread(() -> web.evaluateJavascript(s, null)); }
    String esc(String s) { return (s == null ? "" : s).replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " "); }
    static String norm(String s) { return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim().toLowerCase(Locale.ROOT); }
    static String cleanCode(String s) { if (s == null) return ""; s = s.trim(); return s.replaceFirst("\\.0$", ""); }
    void toast(String m, boolean ok) { js("toastMsg('" + esc(m) + "'," + ok + ")"); }

    void playOk() {
        if (!prefs.getBoolean("sound_ok", true)) return;
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90);
        new Handler(Looper.getMainLooper()).postDelayed(() -> tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 100), 110);
    }
    void playError() {
        if (!prefs.getBoolean("sound_error", true)) return;
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 100);
        new Handler(Looper.getMainLooper()).postDelayed(() -> tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 100), 130);
        new Handler(Looper.getMainLooper()).postDelayed(() -> tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 100), 260);
    }
    void playVictory() {
        if (!prefs.getBoolean("sound_ok", true)) return;
        int[] tones = {ToneGenerator.TONE_PROP_BEEP, ToneGenerator.TONE_PROP_ACK, ToneGenerator.TONE_PROP_BEEP2, ToneGenerator.TONE_SUP_RINGTONE};
        for (int i = 0; i < tones.length; i++) {
            final int t = tones[i];
            new Handler(Looper.getMainLooper()).postDelayed(() -> tone.startTone(t, 140), i * 155L);
        }
    }

    void picker(int requestCode, boolean create, String suggested, String... mimeTypes) {
        Intent i = new Intent(create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mimeTypes.length == 1 ? mimeTypes[0] : "*/*");
        if (mimeTypes.length > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        if (create && suggested != null) i.putExtra(Intent.EXTRA_TITLE, suggested);
        startActivityForResult(i, requestCode);
    }

    @Override protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (c != RESULT_OK || d == null || d.getData() == null) return;
        Uri u = d.getData();
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
        new Thread(() -> {
            try {
                if (r == REPORT) importReport(u);
                else if (r == LOC) importLocations(u);
                else if (r == NEG) importNegatives(u);
                else if (r == EXPORT) exportXlsx(u, prefs.getString("export_scope", "FALTANDO"));
            } catch (OutOfMemoryError e) {
                System.gc();
                toast("Arquivo muito grande para a memória do aparelho. Tente novamente após fechar outros aplicativos.", false);
                playError();
            } catch (Exception e) {
                toast("Erro: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), false);
                playError();
            }
        }).start();
    }

    String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "arquivo";
    }

    void importReport(Uri u) throws Exception {
        String fileName = displayName(u);
        List<ItemIn> items;
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            try (InputStream in = getContentResolver().openInputStream(u)) { items = parseAmazonPdf(in, fileName); }
        } else {
            List<List<String>> rows;
            try (InputStream in = getContentResolver().openInputStream(u)) { rows = Xlsx.read(in); }
            items = parseExcelReport(rows, fileName);
        }
        if (items.isEmpty()) throw new Exception("Nenhum produto bipável encontrado no relatório");
        String base = fileName.replaceFirst("(?i)\\.(xlsx|xls|pdf)$", "");
        if (base.length() > 28) base = base.substring(0, 28);
        String name = base + " - " + new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date());
        batchId = db.newBatch(name, fileName);
        int inherited = 0;
        for (ItemIn x : items) inherited += db.addItemWithReuse(batchId, x);
        db.applyCatalog(batchId);
        completionShown = false; packageCompletionShown = false;
        final int inheritedFinal = inherited;
        js("setBatch('" + esc(name) + "')");
        requestData();
        String msg = items.size() + " produto(s) importado(s)";
        if (inheritedFinal > 0) msg += " • " + inheritedFinal + " unidade(s) reaproveitada(s)";
        toast(msg, true); playOk();
    }

    List<ItemIn> parseExcelReport(List<List<String>> rows, String source) throws Exception {
        int h = findHeader(rows, new String[]{"n.º de venda", "nº de venda", "titulo do anuncio", "nome cliente", "produto"});
        if (h < 0) throw new Exception("Formato do relatório não reconhecido");
        Header cols = new Header(rows.get(h));
        boolean ml = cols.has("n.º de venda", "nº de venda", "numero da venda") || cols.has("titulo do anuncio");
        List<ItemIn> out = new ArrayList<>();
        for (int i = h + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            ItemIn x = new ItemIn();
            if (ml) {
                x.order = cols.val(r, "n.º de venda", "nº de venda", "numero da venda");
                x.code = cleanCode(cols.val(r, "sku", "codigo", "código"));
                x.desc = cols.val(r, "titulo do anuncio", "título do anúncio", "descricao", "descrição");
                x.buyer = cols.val(r, "comprador", "dados pessoais ou da empresa");
                x.doc = cleanCode(cols.val(r, "cpf", "tipo e numero do documento", "tipo e número do documento"));
                x.platform = cols.val(r, "canal de venda"); if (x.platform.isEmpty()) x.platform = "Mercado Livre";
                x.qty = parseInt(cols.val(r, "unidades", "quantidade", "qtd"), 1);
                x.price = parseDouble(cols.val(r, "preco unitario de venda do anuncio (brl)", "preço unitário de venda do anúncio (brl)"), 0);
                x.tracking = cols.val(r, "numero de rastreamento", "número de rastreamento");
            } else {
                x.order = cols.val(r, "pedido cliente", "pedido erp", "pedido ss", "pedido original", "pedido");
                x.code = cleanCode(cols.val(r, "produto", "ean", "codigo de barras", "código de barras", "sku"));
                x.desc = cols.val(r, "descricao", "descrição", "produto descricao", "produto descrição");
                x.buyer = cols.val(r, "nome cliente", "comprador");
                x.doc = cleanCode(cols.val(r, "cpf/cnpj", "cpf", "documento"));
                x.platform = cols.val(r, "canal", "origem"); if (x.platform.isEmpty()) x.platform = "Pedidos";
                x.qty = parseInt(cols.val(r, "quantidade", "unidades", "qtd"), 1);
                x.price = parseDouble(cols.val(r, "preco", "preço"), 0);
                x.nerus = cols.val(r, "localizacao", "localização");
                x.location = cols.val(r, "localizacao ss", "localização ss", "localizacao simpleset", "localização simpleset");
                x.tracking = cols.val(r, "numero de rastreamento", "número de rastreamento", "rastreamento");
            }
            x.source = source;
            if (x.code.isEmpty()) continue;
            if (x.desc.isEmpty()) x.desc = x.code;
            out.add(x);
        }
        return out;
    }

    List<ItemIn> parseAmazonPdf(InputStream in, String source) throws Exception {
        List<ItemIn> out = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(in)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = stripper.getText(doc);
                List<String> lines = new ArrayList<>();
                for (String line : text.split("\\R")) { line = line.replaceAll("\\s+", " ").trim(); if (!line.isEmpty()) lines.add(line); }
                String order = "", buyer = "";
                for (int i = 0; i < lines.size(); i++) {
                    Matcher om = Pattern.compile("ID\\s+do\\s+pedido\\s*:\\s*([0-9-]+)", Pattern.CASE_INSENSITIVE).matcher(lines.get(i));
                    if (om.find()) order = om.group(1).trim();
                    if ((norm(lines.get(i)).equals(norm("Enviar para:")) || norm(lines.get(i)).equals(norm("Endereço de entrega:"))) && i + 1 < lines.size()) buyer = lines.get(i + 1);
                }
                if (order.isEmpty()) continue;
                for (int i = 0; i < lines.size(); i++) {
                    if (!norm(lines.get(i)).contains("detalhes do produto")) continue;
                    i++;
                    while (i < lines.size()) {
                        String line = lines.get(i);
                        if (line.startsWith("Obrigado por comprar") || line.startsWith("Pré-pago")) break;
                        Matcher q = Pattern.compile("^(\\d+)\\s+(.+)$").matcher(line);
                        if (!q.find()) { i++; continue; }
                        ItemIn x = new ItemIn(); x.qty = parseInt(q.group(1), 1); x.order = order; x.buyer = buyer; x.platform = "Amazon"; x.source = source + " - página " + page;
                        StringBuilder desc = new StringBuilder(q.group(2).trim());
                        i++;
                        while (i < lines.size()) {
                            String cur = lines.get(i);
                            Matcher sm = Pattern.compile("^SKU\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE).matcher(cur);
                            if (sm.find()) { x.code = cleanCode(sm.group(1)); i++; break; }
                            if (!cur.startsWith("ASIN:") && !cur.startsWith("Condição:") && !cur.startsWith("ID do item do pedido:")) desc.append(' ').append(cur);
                            i++;
                        }
                        x.desc = desc.toString().replaceAll("\\s+", " ").trim();
                        while (i < lines.size()) {
                            String cur = lines.get(i);
                            Matcher pm = Pattern.compile("R\\$\\s*([0-9.,]+)").matcher(cur);
                            if (pm.find() && x.price == 0) x.price = parseDouble(pm.group(1), 0);
                            if (i + 1 < lines.size() && Pattern.matches("^\\d+\\s+.+", lines.get(i + 1)) && !norm(lines.get(i + 1)).contains("subtotal")) break;
                            if (cur.startsWith("Pré-pago") || cur.startsWith("Obrigado por comprar")) break;
                            i++;
                        }
                        if (!x.code.isEmpty()) out.add(x);
                        i++;
                    }
                    break;
                }
            }
        }
        if (out.isEmpty()) throw new Exception("Não encontrei ID do pedido, SKU e quantidade no PDF da Amazon");
        return out;
    }

    void importLocations(Uri u) throws Exception {
        List<List<String>> rows;
        try (InputStream in = getContentResolver().openInputStream(u)) { rows = Xlsx.read(in); }
        int h = findHeader(rows, new String[]{"localizacao ss", "localização ss", "localizacao simpleset", "codigo de barras", "isbn", "ean"});
        if (h < 0) throw new Exception("Arquivo de localizações não reconhecido");
        Header c = new Header(rows.get(h));
        int count = 0, changed = 0;
        for (int i = h + 1; i < rows.size(); i++) {
            String code = cleanCode(c.val(rows.get(i), "isbn", "ean", "cod_barras", "codigo de barras", "código de barras", "produto", "sku", "codigo", "código"));
            String loc = c.val(rows.get(i), "localizacao ss", "localização ss", "localizacao simpleset", "localização simpleset");
            if (!code.isEmpty() && !loc.isEmpty()) { if (db.putLocation(code, loc)) changed++; count++; }
        }
        if (count == 0) throw new Exception("Nenhuma localização SS preenchida foi encontrada");
        db.applyCatalogAll();
        requestData();
        toast(count + " códigos importados • " + changed + " localização(ões) alterada(s)", true); playOk();
    }

    void importNegatives(Uri u) throws Exception {
        if (batchId == 0) throw new Exception("Selecione uma lista primeiro");
        List<List<String>> rows;
        try (InputStream in = getContentResolver().openInputStream(u)) { rows = Xlsx.read(in); }
        int h = findHeader(rows, new String[]{"cod_barras", "codigo de barras", "qtty", "quantidade"});
        if (h < 0) h = 0;
        Header c = new Header(rows.get(h));
        List<NegIn> negatives = new ArrayList<>();
        for (int i = h + 1; i < rows.size(); i++) {
            NegIn n = new NegIn();
            n.code = cleanCode(c.val(rows.get(i), "cod_barras", "codigo de barras", "código de barras", "ean", "isbn"));
            n.qty = parseInt(c.val(rows.get(i), "qtty", "quantidade", "qtd", "estoque"), 0);
            n.product = c.val(rows.get(i), "produto"); n.grade = c.val(rows.get(i), "grade");
            n.lastPurchase = c.val(rows.get(i), "data_ultima_compra", "data ultima compra");
            n.profitCenter = c.val(rows.get(i), "centro_lucro", "centro de lucro");
            if (!n.code.isEmpty() && n.qty < 0) negatives.add(n);
        }
        if (negatives.isEmpty()) throw new Exception("Nenhum produto com quantidade negativa foi encontrado");
        int[] result = db.applyNegatives(batchId, negatives);
        requestData();
        toast(result[0] + " linha(s) marcada(s) • " + result[1] + " código(s) sem correspondência", true); playOk();
    }

    int findHeader(List<List<String>> rows, String[] keys) {
        for (int i = 0; i < Math.min(rows.size(), 35); i++) {
            String all = norm(String.join("|", rows.get(i)));
            for (String k : keys) if (all.contains(norm(k))) return i;
        }
        return -1;
    }
    static int parseInt(String s, int d) { try { return (int)Math.round(parseDouble(s, d)); } catch (Exception e) { return d; } }
    static double parseDouble(String s, double d) {
        if (s == null || s.trim().isEmpty()) return d;
        try {
            String x = s.trim().replace("R$", "").replace(" ", "");
            if (x.contains(",")) x = x.replace(".", "").replace(",", ".");
            return Double.parseDouble(x);
        } catch (Exception e) { return d; }
    }

    void requestData() {
        if (batchId <= 0) {
            js("setData('[]');setSummary('{\"total\":0,\"checked\":0,\"missing\":0,\"excess\":0,\"percent\":0}')");
            return;
        }
        String arr = mode.equals("packages") ? db.packageItemsJson(batchId) : db.itemsJson(batchId);
        String sum = mode.equals("packages") ? db.packageSummaryJson(batchId) : db.summaryJson(batchId);
        js("setData('" + esc(arr) + "');setSummary('" + esc(sum) + "')");
    }

    void checkCompletion() {
        if (batchId == 0) return;
        if (mode.equals("packages")) {
            int[] s = db.packageSummary(batchId);
            if (s[0] > 0 && s[1] >= s[0] && !packageCompletionShown) {
                packageCompletionShown = true; playVictory();
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Todos os pacotes foram conferidos!")
                    .setMessage("Conferência de pacotes 100% concluída.\n\nTotal: " + s[0] + " pacote(s) embalado(s) com sucesso.")
                    .setPositiveButton("OK", null).show());
            } else if (s[1] < s[0]) packageCompletionShown = false;
        } else {
            int[] s = db.summary(batchId);
            if (s[0] > 0 && s[1] >= s[0] && !completionShown) {
                completionShown = true; playVictory();
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Todos os pedidos foram concluídos!")
                    .setMessage("Conferência 100% concluída.\n\n" + s[1] + " de " + s[0] + " item(ns) conferido(s).")
                    .setPositiveButton("OK", null).show());
            } else if (s[1] < s[0]) completionShown = false;
        }
    }

    void chooseExportScope() {
        if (batchId == 0) { toast("Selecione uma lista", false); return; }
        final String[] labels = {"Faltando", "Todos", "Pendentes", "Parciais", "Conferidos", "Negativos"};
        final String[] scopes = {"FALTANDO", "TODOS", "PENDENTES", "PARCIAIS", "CONFERIDOS", "NEGATIVOS"};
        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Exportar lista").setItems(labels, (d, which) -> {
            prefs.edit().putString("export_scope", scopes[which]).apply();
            String fn = "lista_" + scopes[which].toLowerCase(Locale.ROOT) + "_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date()) + ".xlsx";
            picker(EXPORT, true, fn, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }).show());
    }

    void exportXlsx(Uri u, String scope) throws Exception {
        List<String[]> rows = db.exportRows(batchId, scope, mode.equals("packages"));
        if (rows.size() <= 1) throw new Exception("Nenhum item encontrado para " + scope.toLowerCase(Locale.ROOT));
        try (OutputStream out = getContentResolver().openOutputStream(u)) { Xlsx.write(out, rows, mode.equals("packages") ? "Pacotes" : "Itens"); }
        toast((rows.size() - 1) + " linha(s) exportada(s) com sucesso", true); playOk();
    }

    void printMissing() {
        if (batchId == 0) { toast("Selecione uma lista", false); return; }
        List<String[]> rows = db.exportRows(batchId, "FALTANDO", mode.equals("packages"));
        if (rows.size() <= 1) { toast("Nenhum item faltando", true); return; }
        StringBuilder h = new StringBuilder("<html><head><meta charset='utf-8'><style>body{font-family:Arial}table{border-collapse:collapse;width:100%;font-size:10px}th,td{border:1px solid #777;padding:5px}th{background:#e9eef4}</style></head><body><h2>Lista de faltantes</h2><table>");
        for (int r = 0; r < rows.size(); r++) { h.append("<tr>"); for (String cell : rows.get(r)) h.append(r == 0 ? "<th>" : "<td>").append(html(cell)).append(r == 0 ? "</th>" : "</td>"); h.append("</tr>"); }
        h.append("</table></body></html>");
        runOnUiThread(() -> {
            printWeb = new WebView(this); printWeb.getSettings().setJavaScriptEnabled(false);
            printWeb.setWebViewClient(new WebViewClient(){ @Override public void onPageFinished(WebView view,String url){
                PrintManager pm=(PrintManager)getSystemService(Context.PRINT_SERVICE);
                pm.print("Conferente - Faltantes", view.createPrintDocumentAdapter("Faltantes"), new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape()).build());
            }});
            printWeb.loadDataWithBaseURL(null, h.toString(), "text/HTML", "UTF-8", null);
        });
    }

    static String html(String s) { return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    void loadCoverAsync(int id, boolean force) {
        selectedCoverUrl = "";
        Map<String,String> info = db.itemInfo(id);
        final String code = info.get("code"), desc = info.get("description");
        new Thread(() -> {
            String url = "";
            try {
                String q = !code.isEmpty() ? "isbn:" + code : desc;
                URL gu = new URL("https://www.googleapis.com/books/v1/volumes?q=" + URLEncoder.encode(q, "UTF-8") + "&maxResults=5");
                HttpURLConnection c = (HttpURLConnection) gu.openConnection(); c.setConnectTimeout(4500); c.setReadTimeout(4500); c.setRequestProperty("User-Agent", "ConferentePedidosAndroid/31.3");
                if (c.getResponseCode() == 200) {
                    String body = readAll(c.getInputStream());
                    JSONObject root = new JSONObject(body); JSONArray items = root.optJSONArray("items");
                    if (items != null) for (int i=0;i<items.length() && url.isEmpty();i++) {
                        JSONObject vi = items.getJSONObject(i).optJSONObject("volumeInfo"); if (vi == null) continue;
                        JSONObject links = vi.optJSONObject("imageLinks"); if (links == null) continue;
                        url = links.optString("extraLarge", links.optString("large", links.optString("medium", links.optString("thumbnail", ""))));
                    }
                }
            } catch (Exception ignored) {}
            if (url.isEmpty() && code != null && code.matches("\\d{10}|\\d{13}")) url = "https://covers.openlibrary.org/b/isbn/" + code + "-L.jpg?default=false";
            selectedCoverUrl = url == null ? "" : url.replace("http://", "https://");
            js("setCover('" + esc(selectedCoverUrl) + "')");
        }).start();
    }

    static String readAll(InputStream in) throws Exception { ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[]x=new byte[8192]; int n; while((n=in.read(x))>0)b.write(x,0,n); return b.toString("UTF-8"); }

    public class Bridge {
        @JavascriptInterface public void importReport() { picker(REPORT, false, null, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf"); }
        @JavascriptInterface public void importLocations() { picker(LOC, false, null, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); }
        @JavascriptInterface public void importNegatives() { if (batchId==0){toast("Selecione uma lista primeiro",false);return;} picker(NEG, false, null, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); }
        @JavascriptInterface public void exportMissing() { chooseExportScope(); }
        @JavascriptInterface public void requestData() { MainActivity.this.requestData(); }
        @JavascriptInterface public void setMode(String m) { mode = m; requestData(); }
        @JavascriptInterface public void scan(String raw, String m) {
            if (batchId == 0) { toast("Importe ou selecione uma lista", false); playError(); return; }
            String code = cleanCode(raw);
            Map<String,String> r = m.equals("packages") ? db.scanPackage(batchId, code) : db.scanItem(batchId, code);
            boolean ok = Boolean.parseBoolean(r.get("ok"));
            if (ok) playOk(); else playError();
            try {
                JSONObject j = new JSONObject(); j.put("ok", ok); j.put("message", r.get("message")); j.put("code", code); j.put("time", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
                js("scanResult('" + esc(j.toString()) + "')");
            } catch (Exception ignored) { requestData(); }
            if (r.containsKey("itemId")) { try { selectedId = Integer.parseInt(r.get("itemId")); js("selectItem("+selectedId+")"); } catch(Exception ignored){} }
            checkCompletion();
        }
        @JavascriptInterface public void newBatch() { runOnUiThread(() -> { final EditText e = new EditText(MainActivity.this); e.setHint("Nome da lista"); new AlertDialog.Builder(MainActivity.this).setTitle("Nova lista").setView(e).setPositiveButton("Criar", (d,w) -> { batchId = db.newBatch(e.getText().toString().trim().isEmpty()?"Nova lista":e.getText().toString().trim(), ""); completionShown=false;packageCompletionShown=false;js("setBatch('"+esc(db.batchName(batchId))+"')");requestData(); }).setNegativeButton("Cancelar",null).show(); }); }
        @JavascriptInterface public void chooseBatch() { runOnUiThread(() -> { List<Integer> ids=db.batchIds();List<String> names=db.batchNames();if(ids.isEmpty()){toast("Nenhuma lista salva",false);return;}new AlertDialog.Builder(MainActivity.this).setTitle("Selecionar lista").setItems(names.toArray(new String[0]),(d,w)->{batchId=ids.get(w);completionShown=false;packageCompletionShown=false;js("setBatch('"+esc(names.get(w))+"')");requestData();}).show(); }); }
        @JavascriptInterface public void renameBatch() { if(batchId==0){toast("Selecione uma lista",false);return;} runOnUiThread(() -> { EditText e=new EditText(MainActivity.this);e.setText(db.batchName(batchId));new AlertDialog.Builder(MainActivity.this).setTitle("Renomear lista").setView(e).setPositiveButton("Salvar",(d,w)->{String n=e.getText().toString().trim();if(!n.isEmpty()){db.renameBatch(batchId,n);js("setBatch('"+esc(n)+"')");}}).setNegativeButton("Cancelar",null).show(); }); }
        @JavascriptInterface public void deleteBatch() { if(batchId==0)return; runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this).setTitle("Excluir lista?").setMessage("A lista e toda a conferência desta lista serão removidas.").setPositiveButton("Excluir",(d,w)->{db.deleteBatch(batchId);batchId=db.latestBatchId();js("setBatch('"+esc(batchId>0?db.batchName(batchId):"Selecione uma lista")+"')");requestData();}).setNegativeButton("Cancelar",null).show()); }
        @JavascriptInterface public void undoScan() { if(batchId==0)return; boolean ok=db.undoLast(batchId,mode); if(ok){requestData();toast("Última conferência desfeita",true);completionShown=false;packageCompletionShown=false;} else toast("Não há conferência para desfazer",false); }
        @JavascriptInterface public void refreshLocations() { if(batchId>0){db.applyCatalog(batchId);requestData();toast("Localizações atualizadas",true);} }
        @JavascriptInterface public void selectItem(int id) { selectedId=id; loadCoverAsync(id,false); }
        @JavascriptInterface public void openCover() { if(selectedId==0)return; if(selectedCoverUrl==null||selectedCoverUrl.isEmpty()){loadCoverAsync(selectedId,true);toast("Buscando capa...",true);return;} try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(selectedCoverUrl)));}catch(Exception e){toast("Não foi possível abrir a imagem",false);} }
        @JavascriptInterface public void searchWeb() { if(selectedId==0)return; try{Map<String,String>x=db.itemInfo(selectedId);String q=x.get("code")+" "+x.get("description");startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?tbm=isch&q="+URLEncoder.encode(q,"UTF-8"))));}catch(Exception ignored){} }
        @JavascriptInterface public void coverSearch() { if(selectedId==0){toast("Selecione um item primeiro",false);return;} toast("Buscando uma nova capa...",true);loadCoverAsync(selectedId,true); }
        @JavascriptInterface public void soundSettings() { runOnUiThread(() -> { LinearLayout l=new LinearLayout(MainActivity.this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(48,20,48,8);CheckBox ok=new CheckBox(MainActivity.this);ok.setText("Som de conferência correta");ok.setChecked(prefs.getBoolean("sound_ok",true));CheckBox er=new CheckBox(MainActivity.this);er.setText("Som de erro");er.setChecked(prefs.getBoolean("sound_error",true));l.addView(ok);l.addView(er);new AlertDialog.Builder(MainActivity.this).setTitle("Configurações de som").setView(l).setPositiveButton("Salvar",(d,w)->prefs.edit().putBoolean("sound_ok",ok.isChecked()).putBoolean("sound_error",er.isChecked()).apply()).setNegativeButton("Cancelar",null).show(); }); }
        @JavascriptInterface public void printMissing() { MainActivity.this.printMissing(); }
        @JavascriptInterface public void about() { runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this).setTitle("Conferente Pedidos Online").setMessage("Versão V31.3 Android Nativo\n\nItens, pacotes, Mercado Livre, Amazon PDF, SimpleSet, negativos, histórico, exportação, impressão e capas.").setPositiveButton("OK",null).show()); }
    }

    static class ItemIn { String order="",buyer="",doc="",platform="",code="",desc="",tracking="",location="",nerus="",source=""; int qty=1; double price=0; }
    static class NegIn { String code="",product="",grade="",lastPurchase="",profitCenter=""; int qty=0; }

    static class Header {
        Map<String,Integer> m = new LinkedHashMap<>();
        Header(List<String> h) { for(int i=0;i<h.size();i++){String k=norm(h.get(i)); if(!k.isEmpty() && !m.containsKey(k))m.put(k,i);} }
        boolean has(String... ks){ for(String k:ks) if(m.containsKey(norm(k))) return true; return false; }
        String val(List<String> r,String...ks){ for(String k:ks){Integer i=m.get(norm(k));if(i!=null&&i<r.size())return r.get(i)==null?"":r.get(i).trim();}return""; }
    }

    static class DB extends SQLiteOpenHelper {
        DB(Context c){super(c,"conferente_v31.db",null,4);}
        @Override public void onCreate(SQLiteDatabase d){
            d.execSQL("CREATE TABLE batches(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,source_file TEXT DEFAULT '',created TEXT DEFAULT CURRENT_TIMESTAMP)");
            d.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT,batch_id INTEGER,order_id TEXT,buyer TEXT,doc TEXT,platform TEXT,code TEXT,sku TEXT DEFAULT '',description TEXT,qty INTEGER,checked INTEGER DEFAULT 0,unit_price REAL DEFAULT 0,location TEXT DEFAULT '',nerus TEXT DEFAULT '',negative INTEGER DEFAULT 0,negative_product TEXT DEFAULT '',negative_grade TEXT DEFAULT '',negative_last_purchase TEXT DEFAULT '',negative_profit_center TEXT DEFAULT '',tracking TEXT DEFAULT '',source TEXT DEFAULT '',status TEXT DEFAULT 'PENDENTE')");
            d.execSQL("CREATE TABLE locations(code TEXT PRIMARY KEY,location TEXT,updated TEXT DEFAULT CURRENT_TIMESTAMP)");
            d.execSQL("CREATE TABLE packages(id INTEGER PRIMARY KEY AUTOINCREMENT,batch_id INTEGER,order_id TEXT DEFAULT '',tracking TEXT,key TEXT,checked INTEGER DEFAULT 0,checked_at TEXT,UNIQUE(batch_id,key))");
            d.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT,batch_id INTEGER,item_id INTEGER,kind TEXT,created TEXT DEFAULT CURRENT_TIMESTAMP)");
            d.execSQL("CREATE INDEX idx_items_batch_code ON items(batch_id,code)"); d.execSQL("CREATE INDEX idx_items_order ON items(order_id,code)"); d.execSQL("CREATE INDEX idx_pkg_key ON packages(batch_id,key)");
        }
        void alter(SQLiteDatabase d,String sql){try{d.execSQL(sql);}catch(Exception ignored){}}
        @Override public void onUpgrade(SQLiteDatabase d,int old,int now){
            alter(d,"ALTER TABLE batches ADD COLUMN source_file TEXT DEFAULT ''");
            alter(d,"ALTER TABLE items ADD COLUMN sku TEXT DEFAULT ''");alter(d,"ALTER TABLE items ADD COLUMN unit_price REAL DEFAULT 0");alter(d,"ALTER TABLE items ADD COLUMN nerus TEXT DEFAULT ''");alter(d,"ALTER TABLE items ADD COLUMN negative_product TEXT DEFAULT ''");alter(d,"ALTER TABLE items ADD COLUMN negative_grade TEXT DEFAULT ''");alter(d,"ALTER TABLE items ADD COLUMN negative_last_purchase TEXT DEFAULT ''");alter(d,"ALTER TABLE items ADD COLUMN negative_profit_center TEXT DEFAULT ''");alter(d,"ALTER TABLE items ADD COLUMN source TEXT DEFAULT ''");alter(d,"ALTER TABLE items ADD COLUMN status TEXT DEFAULT 'PENDENTE'");
            alter(d,"ALTER TABLE locations ADD COLUMN updated TEXT DEFAULT CURRENT_TIMESTAMP");alter(d,"ALTER TABLE packages ADD COLUMN order_id TEXT DEFAULT ''");alter(d,"ALTER TABLE packages ADD COLUMN checked_at TEXT");
            alter(d,"CREATE INDEX idx_items_batch_code ON items(batch_id,code)");alter(d,"CREATE INDEX idx_items_order ON items(order_id,code)");alter(d,"CREATE INDEX idx_pkg_key ON packages(batch_id,key)");
        }
        int newBatch(String n,String src){ContentValues v=new ContentValues();v.put("name",n);v.put("source_file",src);return(int)getWritableDatabase().insert("batches",null,v);}
        int latestBatchId(){Cursor c=getReadableDatabase().rawQuery("SELECT id FROM batches ORDER BY id DESC LIMIT 1",null);int x=0;if(c.moveToFirst())x=c.getInt(0);c.close();return x;}
        String batchName(int id){Cursor c=getReadableDatabase().rawQuery("SELECT name FROM batches WHERE id=?",new String[]{""+id});String s="";if(c.moveToFirst())s=c.getString(0);c.close();return s;}
        List<Integer> batchIds(){List<Integer>a=new ArrayList<>();Cursor c=getReadableDatabase().rawQuery("SELECT id FROM batches ORDER BY id DESC",null);while(c.moveToNext())a.add(c.getInt(0));c.close();return a;}
        List<String> batchNames(){List<String>a=new ArrayList<>();Cursor c=getReadableDatabase().rawQuery("SELECT name FROM batches ORDER BY id DESC",null);while(c.moveToNext())a.add(c.getString(0));c.close();return a;}
        void renameBatch(int id,String n){ContentValues v=new ContentValues();v.put("name",n);getWritableDatabase().update("batches",v,"id=?",new String[]{""+id});}
        void deleteBatch(int id){SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{d.delete("history","batch_id=?",new String[]{""+id});d.delete("packages","batch_id=?",new String[]{""+id});d.delete("items","batch_id=?",new String[]{""+id});d.delete("batches","id=?",new String[]{""+id});d.setTransactionSuccessful();}finally{d.endTransaction();}}

        int addItemWithReuse(int b,ItemIn x){SQLiteDatabase d=getWritableDatabase();int inherited=0;int prev=0;if(!x.order.trim().isEmpty()){Cursor c=d.rawQuery("SELECT COALESCE(MAX(checked),0) FROM items WHERE batch_id<>? AND TRIM(order_id)=TRIM(?) AND (code=? OR sku=?)",new String[]{""+b,x.order,x.code,x.code});if(c.moveToFirst())prev=c.getInt(0);c.close();}int checked=Math.min(Math.max(prev,0),Math.max(x.qty,1));inherited=checked;ContentValues v=new ContentValues();v.put("batch_id",b);v.put("order_id",x.order);v.put("buyer",x.buyer);v.put("doc",x.doc);v.put("platform",x.platform);v.put("code",x.code);v.put("sku",x.code);v.put("description",x.desc);v.put("qty",Math.max(x.qty,1));v.put("checked",checked);v.put("unit_price",x.price);v.put("location",x.location);v.put("nerus",x.nerus);v.put("tracking",x.tracking);v.put("source",x.source);v.put("status",checked>=x.qty?"CONFERIDO":checked>0?"PARCIAL":"PENDENTE");d.insert("items",null,v);String k=trackKey(x.tracking);if(!k.isEmpty()){ContentValues p=new ContentValues();p.put("batch_id",b);p.put("order_id",x.order);p.put("tracking",x.tracking);p.put("key",k);d.insertWithOnConflict("packages",null,p,SQLiteDatabase.CONFLICT_IGNORE);}return inherited;}
        static String trackKey(String s){if(s==null)return"";Matcher m=Pattern.compile("(\\d{8,})").matcher(s.replaceAll("\\s","").toUpperCase(Locale.ROOT));return m.find()?m.group(1):"";}

        boolean putLocation(String code,String loc){SQLiteDatabase d=getWritableDatabase();Cursor c=d.rawQuery("SELECT location FROM locations WHERE code=?",new String[]{code});String old="";if(c.moveToFirst())old=c.getString(0);c.close();ContentValues v=new ContentValues();v.put("code",code);v.put("location",loc);v.put("updated",new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date()));d.insertWithOnConflict("locations",null,v,SQLiteDatabase.CONFLICT_REPLACE);return!loc.equals(old);}
        void applyCatalog(int b){getWritableDatabase().execSQL("UPDATE items SET location=COALESCE((SELECT location FROM locations WHERE locations.code=items.code OR locations.code=items.sku LIMIT 1),location) WHERE batch_id=?",new Object[]{b});}
        void applyCatalogAll(){getWritableDatabase().execSQL("UPDATE items SET location=COALESCE((SELECT location FROM locations WHERE locations.code=items.code OR locations.code=items.sku LIMIT 1),location)");}

        int[] applyNegatives(int b,List<NegIn> rows){SQLiteDatabase d=getWritableDatabase();d.execSQL("UPDATE items SET negative=0,negative_product='',negative_grade='',negative_last_purchase='',negative_profit_center='' WHERE batch_id=?",new Object[]{b});int matched=0,unmatched=0;for(NegIn n:rows){ContentValues v=new ContentValues();v.put("negative",n.qty);v.put("negative_product",n.product);v.put("negative_grade",n.grade);v.put("negative_last_purchase",n.lastPurchase);v.put("negative_profit_center",n.profitCenter);int x=d.update("items",v,"batch_id=? AND (code=? OR sku=?)",new String[]{""+b,n.code,n.code});if(x>0)matched+=x;else unmatched++;}return new int[]{matched,unmatched};}

        Map<String,String> scanItem(int b,String raw){String code=cleanCode(raw);Map<String,String>r=new HashMap<>();SQLiteDatabase d=getWritableDatabase();Cursor c=d.rawQuery("SELECT id,qty,checked,description FROM items WHERE batch_id=? AND (code=? OR sku=?) AND checked<qty ORDER BY id LIMIT 1",new String[]{""+b,code,code});if(c.moveToFirst()){int id=c.getInt(0),q=c.getInt(1),ch=c.getInt(2)+1;String desc=c.getString(3);d.execSQL("UPDATE items SET checked=?,status=? WHERE id=?",new Object[]{ch,ch>=q?"CONFERIDO":"PARCIAL",id});d.execSQL("INSERT INTO history(batch_id,item_id,kind) VALUES(?,?,'item')",new Object[]{b,id});r.put("ok","true");r.put("itemId",""+id);r.put("message",ch>=q?"Conferido: "+desc:"Bip "+ch+"/"+q+" • "+desc);}else{Cursor x=d.rawQuery("SELECT id FROM items WHERE batch_id=? AND (code=? OR sku=?)",new String[]{""+b,code,code});boolean exists=x.moveToFirst();x.close();r.put("ok","false");r.put("message",exists?"Quantidade já conferida":"Código não encontrado");}c.close();return r;}
        Map<String,String> scanPackage(int b,String raw){Map<String,String>r=new HashMap<>();String k=trackKey(raw);if(k.isEmpty()){r.put("ok","false");r.put("message","Rastreio inválido");return r;}SQLiteDatabase d=getWritableDatabase();Cursor c=d.rawQuery("SELECT id,checked,order_id FROM packages WHERE batch_id=? AND key=?",new String[]{""+b,k});if(!c.moveToFirst()){r.put("ok","false");r.put("message","Pacote não encontrado");}else if(c.getInt(1)>0){r.put("ok","false");r.put("message","Pacote já conferido");}else{int id=c.getInt(0);String order=c.getString(2);d.execSQL("UPDATE packages SET checked=1,checked_at=CURRENT_TIMESTAMP WHERE id=?",new Object[]{id});d.execSQL("INSERT INTO history(batch_id,item_id,kind) VALUES(?,?,'package')",new Object[]{b,id});r.put("ok","true");r.put("message","Pacote conferido • "+(order==null?"":order));}c.close();return r;}
        boolean undoLast(int b,String mode){SQLiteDatabase d=getWritableDatabase();String kind=mode.equals("packages")?"package":"item";Cursor c=d.rawQuery("SELECT id,item_id FROM history WHERE batch_id=? AND kind=? ORDER BY id DESC LIMIT 1",new String[]{""+b,kind});if(!c.moveToFirst()){c.close();return false;}int hid=c.getInt(0),id=c.getInt(1);if(kind.equals("item")){Cursor x=d.rawQuery("SELECT checked,qty FROM items WHERE id=?",new String[]{""+id});if(x.moveToFirst()){int ch=Math.max(0,x.getInt(0)-1),q=x.getInt(1);d.execSQL("UPDATE items SET checked=?,status=? WHERE id=?",new Object[]{ch,ch==0?"PENDENTE":ch>=q?"CONFERIDO":"PARCIAL",id});}x.close();}else d.execSQL("UPDATE packages SET checked=0,checked_at=NULL WHERE id=?",new Object[]{id});d.delete("history","id=?",new String[]{""+hid});c.close();return true;}

        String itemsJson(int b){JSONArray a=new JSONArray();Cursor c=getReadableDatabase().rawQuery("SELECT id,order_id,buyer,doc,platform,code,description,qty,checked,location,nerus,negative,tracking,source,status,negative_product,negative_grade,negative_last_purchase,negative_profit_center FROM items WHERE batch_id=? ORDER BY platform,order_id,id",new String[]{""+b});while(c.moveToNext()){try{JSONObject o=new JSONObject();int q=c.getInt(7),ch=c.getInt(8);o.put("id",c.getInt(0));o.put("order",nz(c.getString(1)));o.put("buyer",nz(c.getString(2)));o.put("doc",nz(c.getString(3)));o.put("platform",nz(c.getString(4)));o.put("code",nz(c.getString(5)));o.put("description",nz(c.getString(6)));o.put("qty",q);o.put("checked",ch);o.put("missing",Math.max(0,q-ch));o.put("location",nz(c.getString(9)));o.put("nerus",nz(c.getString(10)));o.put("negative",c.getInt(11));o.put("tracking",nz(c.getString(12)));o.put("source",nz(c.getString(13)));o.put("status",nz(c.getString(14)));o.put("negative_product",nz(c.getString(15)));o.put("negative_grade",nz(c.getString(16)));o.put("negative_last_purchase",nz(c.getString(17)));o.put("negative_profit_center",nz(c.getString(18)));a.put(o);}catch(Exception ignored){}}c.close();return a.toString();}
        String packageItemsJson(int b){JSONArray a=new JSONArray();Cursor c=getReadableDatabase().rawQuery("SELECT i.id,i.order_id,i.buyer,i.doc,i.platform,i.code,i.description,i.location,i.nerus,i.negative,i.tracking,i.source,COALESCE(p.checked,0),COALESCE(p.tracking,'') FROM items i LEFT JOIN packages p ON p.batch_id=i.batch_id AND (p.order_id=i.order_id OR p.key=?) WHERE i.batch_id=? ORDER BY i.platform,i.order_id,i.id",new String[]{"__never__",""+b});while(c.moveToNext()){try{JSONObject o=new JSONObject();int checked=c.getInt(12)>0?1:0;o.put("id",c.getInt(0));o.put("order",nz(c.getString(1)));o.put("buyer",nz(c.getString(2)));o.put("doc",nz(c.getString(3)));o.put("platform",nz(c.getString(4)));o.put("code",nz(c.getString(5)));o.put("description",nz(c.getString(6)));o.put("qty",1);o.put("checked",checked);o.put("missing",checked>0?0:1);o.put("location",nz(c.getString(7)));o.put("nerus",nz(c.getString(8)));o.put("negative",c.getInt(9));o.put("tracking",nz(c.getString(13).isEmpty()?c.getString(10):c.getString(13)));o.put("source",nz(c.getString(11)));o.put("status",checked>0?"CONFERIDO":"PENDENTE");a.put(o);}catch(Exception ignored){}}c.close();return a.toString();}
        int[] summary(int b){Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(qty),0),COALESCE(SUM(MIN(qty,checked)),0),COALESCE(SUM(MAX(qty-checked,0)),0),COALESCE(SUM(MAX(checked-qty,0)),0) FROM items WHERE batch_id=?",new String[]{""+b});c.moveToFirst();int[]x={c.getInt(0),c.getInt(1),c.getInt(2),c.getInt(3)};c.close();return x;}
        String summaryJson(int b){int[]x=summary(b);int p=x[0]==0?0:(int)Math.round(x[1]*100.0/x[0]);return"{\"total\":"+x[0]+",\"checked\":"+x[1]+",\"missing\":"+x[2]+",\"excess\":"+x[3]+",\"percent\":"+p+"}";}
        int[] packageSummary(int b){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(checked),0) FROM packages WHERE batch_id=?",new String[]{""+b});c.moveToFirst();int[]x={c.getInt(0),c.getInt(1)};c.close();return x;}
        String packageSummaryJson(int b){int[]x=packageSummary(b);int m=Math.max(0,x[0]-x[1]),p=x[0]==0?0:(int)Math.round(x[1]*100.0/x[0]);return"{\"total\":"+x[0]+",\"checked\":"+x[1]+",\"missing\":"+m+",\"excess\":0,\"percent\":"+p+"}";}

        List<String[]> exportRows(int b,String scope,boolean packages){List<String[]>a=new ArrayList<>();a.add(new String[]{"Código de Barras","Descrição","Quantidade Faltando","Localização SS"});String where="";if(scope.equals("PENDENTES"))where=packages?" AND COALESCE(p.checked,0)=0":" AND i.checked=0";else if(scope.equals("PARCIAIS"))where=packages?" AND 1=0":" AND i.checked>0 AND i.checked<i.qty";else if(scope.equals("FALTANDO"))where=packages?" AND COALESCE(p.checked,0)=0":" AND i.checked<i.qty";else if(scope.equals("CONFERIDOS"))where=packages?" AND COALESCE(p.checked,0)=1":" AND i.checked>=i.qty";else if(scope.equals("NEGATIVOS"))where=" AND i.negative<0";String sql=packages?"SELECT i.code,i.description,CASE WHEN COALESCE(p.checked,0)=1 THEN 0 ELSE 1 END,i.location FROM items i LEFT JOIN packages p ON p.batch_id=i.batch_id AND p.order_id=i.order_id WHERE i.batch_id=?"+where+" ORDER BY i.order_id,i.id":"SELECT i.code,i.description,MAX(i.qty-i.checked,0),i.location FROM items i WHERE i.batch_id=?"+where+" ORDER BY i.order_id,i.id";Cursor c=getReadableDatabase().rawQuery(sql,new String[]{""+b});while(c.moveToNext())a.add(new String[]{nz(c.getString(0)),nz(c.getString(1)),""+c.getInt(2),nz(c.getString(3))});c.close();return a;}
        Map<String,String> itemInfo(int id){Map<String,String>m=new HashMap<>();Cursor c=getReadableDatabase().rawQuery("SELECT code,description FROM items WHERE id=?",new String[]{""+id});if(c.moveToFirst()){m.put("code",nz(c.getString(0)));m.put("description",nz(c.getString(1)));}else{m.put("code","");m.put("description","");}c.close();return m;}
        static String nz(String s){return s==null?"":s;}
    }

    static class Xlsx {
        static List<List<String>> read(InputStream in) throws Exception {
            // Leitor XLSX otimizado para Android.
            // Não cria DOM da planilha inteira: sharedStrings e sheet são processados com SAX.
            byte[] sharedBytes = null;
            byte[] sheetBytes = null;

            try (ZipInputStream zi = new ZipInputStream(in)) {
                ZipEntry e;
                byte[] buf = new byte[32768];
                while ((e = zi.getNextEntry()) != null) {
                    String name = e.getName();
                    if (!"xl/sharedStrings.xml".equals(name) &&
                        !"xl/worksheets/sheet1.xml".equals(name)) {
                        continue;
                    }
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    int n;
                    while ((n = zi.read(buf)) > 0) b.write(buf, 0, n);
                    if ("xl/sharedStrings.xml".equals(name)) sharedBytes = b.toByteArray();
                    else sheetBytes = b.toByteArray();
                }
            }

            if (sheetBytes == null) throw new Exception("Planilha sem primeira aba");

            final List<String> shared = new ArrayList<>();
            if (sharedBytes != null) {
                javax.xml.parsers.SAXParserFactory sf = javax.xml.parsers.SAXParserFactory.newInstance();
                sf.setNamespaceAware(false);
                javax.xml.parsers.SAXParser sp = sf.newSAXParser();

                sp.parse(new ByteArrayInputStream(sharedBytes), new org.xml.sax.helpers.DefaultHandler() {
                    boolean inSi = false;
                    boolean inT = false;
                    StringBuilder current = null;

                    @Override public void startElement(String uri, String local, String qName, org.xml.sax.Attributes a) {
                        if ("si".equals(qName)) {
                            inSi = true;
                            current = new StringBuilder();
                        } else if (inSi && "t".equals(qName)) {
                            inT = true;
                        }
                    }

                    @Override public void characters(char[] ch, int start, int length) {
                        if (inSi && inT && current != null) current.append(ch, start, length);
                    }

                    @Override public void endElement(String uri, String local, String qName) {
                        if ("t".equals(qName)) {
                            inT = false;
                        } else if ("si".equals(qName)) {
                            shared.add(current == null ? "" : current.toString());
                            current = null;
                            inSi = false;
                        }
                    }
                });
            }

            final List<List<String>> out = new ArrayList<>();
            javax.xml.parsers.SAXParserFactory sf = javax.xml.parsers.SAXParserFactory.newInstance();
            sf.setNamespaceAware(false);
            javax.xml.parsers.SAXParser sp = sf.newSAXParser();

            sp.parse(new ByteArrayInputStream(sheetBytes), new org.xml.sax.helpers.DefaultHandler() {
                List<String> row = null;
                int col = 0;
                String cellType = "";
                boolean inValue = false;
                boolean inInlineText = false;
                StringBuilder value = new StringBuilder();

                @Override public void startElement(String uri, String local, String qName, org.xml.sax.Attributes a) {
                    if ("row".equals(qName)) {
                        row = new ArrayList<>();
                    } else if ("c".equals(qName)) {
                        col = colIndex(a.getValue("r"));
                        cellType = a.getValue("t");
                        if (cellType == null) cellType = "";
                        value.setLength(0);
                    } else if ("v".equals(qName)) {
                        inValue = true;
                        value.setLength(0);
                    } else if ("t".equals(qName) && ("inlineStr".equals(cellType) || "str".equals(cellType))) {
                        inInlineText = true;
                        value.setLength(0);
                    }
                }

                @Override public void characters(char[] ch, int start, int length) {
                    if (inValue || inInlineText) value.append(ch, start, length);
                }

                @Override public void endElement(String uri, String local, String qName) {
                    if ("v".equals(qName)) {
                        inValue = false;
                    } else if ("t".equals(qName)) {
                        inInlineText = false;
                    } else if ("c".equals(qName)) {
                        if (row == null) return;
                        while (row.size() <= col) row.add("");
                        String v = value.toString();
                        if ("s".equals(cellType) && !v.isEmpty()) {
                            try {
                                int x = Integer.parseInt(v.trim());
                                if (x >= 0 && x < shared.size()) v = shared.get(x);
                            } catch (Exception ignored) {}
                        }
                        row.set(col, v);
                    } else if ("row".equals(qName)) {
                        if (row != null) out.add(row);
                        row = null;
                    }
                }
            });

            return out;
        }

        static Document parse(byte[] b) throws Exception {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            try { f.setExpandEntityReferences(false); } catch (Exception ignored) {}

            // Alguns recursos de segurança XML não existem no parser nativo do Android.
            // Aplicamos somente os que o aparelho suportar, sem impedir a leitura do XLSX.
            String[] trueFeatures = {
                "http://apache.org/xml/features/disallow-doctype-decl"
            };
            String[] falseFeatures = {
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd"
            };

            for (String feature : trueFeatures) {
                try { f.setFeature(feature, true); } catch (Exception ignored) {}
            }
            for (String feature : falseFeatures) {
                try { f.setFeature(feature, false); } catch (Exception ignored) {}
            }

            DocumentBuilder builder = f.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader(""))
            );
            return builder.parse(new ByteArrayInputStream(b));
        }
        static int colIndex(String r){int n=0;for(char c:r.toCharArray()){if(!Character.isLetter(c))break;n=n*26+(Character.toUpperCase(c)-'A'+1);}return Math.max(0,n-1);}
        static void write(OutputStream out,List<String[]>rows,String sheetName)throws Exception{ZipOutputStream z=new ZipOutputStream(out);put(z,"[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");put(z,"_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");put(z,"xl/workbook.xml","<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\""+xml(sheetName)+"\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");put(z,"xl/_rels/workbook.xml.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");put(z,"xl/styles.xml","<?xml version=\"1.0\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9EAF7\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"3\"><xf fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf fontId=\"1\" fillId=\"1\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf><xf fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf></cellXfs></styleSheet>");StringBuilder s=new StringBuilder("<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><cols><col min=\"1\" max=\"1\" width=\"22\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"55\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"22\" customWidth=\"1\"/><col min=\"4\" max=\"4\" width=\"24\" customWidth=\"1\"/></cols><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>");for(int r=0;r<rows.size();r++){s.append("<row r=\"").append(r+1).append("\">");for(int c=0;c<rows.get(r).length;c++){String ref=letters(c+1)+(r+1);int style=r==0?1:2;s.append("<c r=\"").append(ref).append("\" t=\"inlineStr\" s=\"").append(style).append("\"><is><t>").append(xml(rows.get(r)[c])).append("</t></is></c>");}s.append("</row>");}s.append("</sheetData><autoFilter ref=\"A1:D").append(rows.size()).append("\"/><pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>");put(z,"xl/worksheets/sheet1.xml",s.toString());z.finish();z.close();}
        static void put(ZipOutputStream z,String n,String s)throws Exception{z.putNextEntry(new ZipEntry(n));z.write(s.getBytes("UTF-8"));z.closeEntry();}
        static String xml(String s){return(s==null?"":s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
        static String letters(int n){String s="";while(n>0){n--;s=(char)('A'+n%26)+s;n/=26;}return s;}
        void insertWithdrawalItem(long batchId,String code,String desc,int expected,String loc){
            SQLiteDatabase d=getWritableDatabase();
            ContentValues v=new ContentValues();
            v.put("batch_id",batchId);
            v.put("code",normalizeDbCode(code));
            v.put("description",desc);
            v.put("expected",expected);
            v.put("checked",0);
            v.put("location_ss",loc);
            v.put("status","PENDENTE");
            d.insert("withdrawal_items",null,v);
        }

        String getBatchType(long batchId){
            try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(type,'PEDIDOS') FROM batches WHERE id=?",new String[]{String.valueOf(batchId)})){
                if(c.moveToFirst()) return c.getString(0);
            }catch(Exception ignored){}
            return "PEDIDOS";
        }

        static String normalizeDbCode(String s){
            if(s==null)return "";
            s=s.trim();
            if(s.endsWith(".0")) s=s.substring(0,s.length()-2);
            return s.replaceAll("\\s+","");
        }

        static class WithdrawalScanResult{
            static final int OK=1, NOT_FOUND=2, LIMIT_REACHED=3;
            int status,checked,expected;
            WithdrawalScanResult(int s,int c,int e){status=s;checked=c;expected=e;}
        }

        WithdrawalScanResult scanWithdrawal(long batchId,String rawCode){
            String code=normalizeDbCode(rawCode);
            SQLiteDatabase d=getWritableDatabase();
            d.beginTransaction();
            try(Cursor c=d.rawQuery("SELECT id,expected,checked FROM withdrawal_items WHERE batch_id=? AND code=? LIMIT 1",
                    new String[]{String.valueOf(batchId),code})){
                if(!c.moveToFirst()){
                    d.setTransactionSuccessful();
                    return new WithdrawalScanResult(WithdrawalScanResult.NOT_FOUND,0,0);
                }
                long id=c.getLong(0);
                int exp=c.getInt(1), chk=c.getInt(2);
                if(chk>=exp){
                    d.setTransactionSuccessful();
                    return new WithdrawalScanResult(WithdrawalScanResult.LIMIT_REACHED,chk,exp);
                }
                chk++;
                ContentValues v=new ContentValues();
                v.put("checked",chk);
                v.put("status",chk>=exp?"CONFERIDO":"PARCIAL");
                d.update("withdrawal_items",v,"id=?",new String[]{String.valueOf(id)});
                d.setTransactionSuccessful();
                return new WithdrawalScanResult(WithdrawalScanResult.OK,chk,exp);
            }finally{ d.endTransaction(); }
        }


    }
}
