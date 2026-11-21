package fr.proutlost.director.api.codec;

import fr.proutlost.director.api.model.JsonValue;
import fr.proutlost.director.api.model.Checks;
import java.math.BigDecimal;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded UTF-8 JSON. No duplicate keys, trailing tokens, malformed Unicode or nonfinite numbers. */
public final class CanonicalJsonV1 {
    public static final int MAX_BYTES=1_048_576, MAX_DEPTH=32, MAX_ITEMS=65_536;
    private CanonicalJsonV1() {}
    public static JsonValue decode(byte[] bytes) {
        Checks.require(bytes.length<=MAX_BYTES,"JSON byte bound");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Parser parser=new Parser(text); JsonValue value=parser.value(0); parser.space();
            Checks.require(parser.pos==text.length(),"trailing JSON"); return value;
        } catch(CharacterCodingException e) { throw new IllegalArgumentException("invalid UTF-8",e); }
    }
    public static byte[] encode(JsonValue value) {
        StringBuilder out=new StringBuilder(); write(value,out,0,new int[1]);
        byte[] bytes=out.toString().getBytes(StandardCharsets.UTF_8);
        Checks.require(bytes.length<=MAX_BYTES,"JSON byte bound"); return bytes;
    }
    private static void bounded(StringBuilder out,int depth,int[] count) {
        Checks.require(depth<=MAX_DEPTH && ++count[0]<=MAX_ITEMS && out.length()<=MAX_BYTES,"JSON bounds");
    }
    private static void write(JsonValue v,StringBuilder out,int depth,int[] count) {
        Objects.requireNonNull(v); bounded(out,depth,count);
        switch(v) {
            case JsonValue.Text t -> quote(t.value(),out);
            case JsonValue.Number n -> out.append(number(n.value()));
            case JsonValue.Bool b -> out.append(b.value());
            case JsonValue.Nil ignored -> out.append("null");
            case JsonValue.Array a -> { out.append('['); boolean first=true; for(var item:a.values()) { if(!first)out.append(','); first=false; write(item,out,depth+1,count); } out.append(']'); }
            case JsonValue.ObjectValue o -> { out.append('{'); boolean first=true; for(var entry:new TreeMap<>(o.values()).entrySet()) { if(!first)out.append(','); first=false; quote(entry.getKey(),out); out.append(':'); write(entry.getValue(),out,depth+1,count); } out.append('}'); }
        }
    }
    // Prefer existing plain goldens; scientific form bounds large normalized coefficients.
    private static String number(BigDecimal n) {
        String plain=n.toPlainString();
        String canonical=plain.length()<=400?plain:n.toString();
        Checks.require(canonical.length()<=400,"numeric bound");return canonical;
    }
    private static void quote(String s,StringBuilder out) {
        Checks.text(s); out.append('"');
        for(int i=0;i<s.length();i++) { char c=s.charAt(i); switch(c) {
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
            case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f"); case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
            default -> { if(c<32) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else out.append(c); }
        }} out.append('"');
    }
    private static final class Parser {
        final String text; int pos,items;
        Parser(String text) { this.text=text; }
        void space() { while(pos<text.length() && " \r\n\t".indexOf(text.charAt(pos))>=0)pos++; }
        boolean take(char c) { space(); if(pos<text.length() && text.charAt(pos)==c){pos++;return true;}return false; }
        void expect(char c) { Checks.require(take(c),"expected "+c+" at "+pos); }
        JsonValue value(int depth) {
            Checks.require(depth<=MAX_DEPTH && ++items<=MAX_ITEMS,"JSON bounds"); space();
            Checks.require(pos<text.length(),"missing value"); char c=text.charAt(pos);
            if(c=='"') return new JsonValue.Text(string());
            if(c=='{') { pos++; Map<String,JsonValue> map=new HashMap<>(); if(!take('}')) { do { space(); String key=string(); expect(':'); JsonValue v=value(depth+1); Checks.require(map.putIfAbsent(key,v)==null,"duplicate key"); } while(take(',')); expect('}'); } return new JsonValue.ObjectValue(map); }
            if(c=='[') { pos++; List<JsonValue> list=new ArrayList<>(); if(!take(']')) { do { list.add(value(depth+1)); }while(take(',')); expect(']'); } return new JsonValue.Array(list); }
            for(String literal:List.of("true","false","null")) if(text.startsWith(literal,pos)) { pos+=literal.length(); return literal.equals("null")?JsonValue.Nil.INSTANCE:new JsonValue.Bool(literal.equals("true")); }
            int start=pos; if(c=='-')pos++;
            Checks.require(pos<text.length(),"number");
            if(text.charAt(pos)=='0')pos++; else { Checks.require(text.charAt(pos)>='1'&&text.charAt(pos)<='9',"number"); digits(); }
            if(pos<text.length()&&text.charAt(pos)=='.') { pos++; int mark=pos; digits(); Checks.require(pos>mark,"fraction"); }
            if(pos<text.length()&&"eE".indexOf(text.charAt(pos))>=0) {pos++;if(pos<text.length()&&"+-".indexOf(text.charAt(pos))>=0)pos++; int mark=pos;digits();Checks.require(pos>mark,"exponent");}
            Checks.require(pos-start<=400,"numeric bound"); return new JsonValue.Number(new BigDecimal(text.substring(start,pos)));
        }
        void digits(){while(pos<text.length()&&text.charAt(pos)>='0'&&text.charAt(pos)<='9')pos++;}
        String string() {
            Checks.require(pos<text.length()&&text.charAt(pos++)=='"',"string");StringBuilder s=new StringBuilder();
            while(pos<text.length()) {char c=text.charAt(pos++);if(c=='"'){Checks.text(s.toString());return s.toString();}
                Checks.require(c>=32,"control character");
                if(c=='\\') {Checks.require(pos<text.length(),"escape");char e=text.charAt(pos++);c=switch(e){
                    case '"','\\','/' -> e;case 'b' -> '\b';case 'f' -> '\f';case 'n' -> '\n';case 'r' -> '\r';case 't' -> '\t';
                    case 'u' -> {Checks.require(pos+4<=text.length(),"unicode escape");String hex=text.substring(pos,pos+4);Checks.require(hex.matches("[0-9a-fA-F]{4}"),"unicode escape");pos+=4;yield (char)Integer.parseInt(hex,16);}
                    default -> throw new IllegalArgumentException("escape");};}
                s.append(c);Checks.require(s.length()<=65536,"string bound");
            }throw new IllegalArgumentException("unterminated string");
        }
    }
}
