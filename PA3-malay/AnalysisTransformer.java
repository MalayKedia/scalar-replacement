import java.util.*;
import soot.*;
import soot.jimple.Stmt;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

import java.security.Key;
import java.util.*;

import java_cup.parse_action;
import java_cup.reduce_action;
import soot.*;
import soot.jimple.AnyNewExpr;
import soot.jimple.AssignStmt;
import soot.jimple.ReturnStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.NullConstant;
import soot.jimple.Constant;
import soot.jimple.Ref;
import soot.jimple.StaticFieldRef;
import soot.jimple.Stmt;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JNewExpr;
import soot.tagkit.SourceLineNumberTag;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.BackwardFlowAnalysis;
import soot.toolkits.scalar.FlowSet;
import soot.toolkits.scalar.ForwardFlowAnalysis;
import soot.util.Cons;

class abstractObj {
    Unit allocSite;

    Set<Integer> callSites;

    public abstractObj(Unit u){
        this.allocSite = u;
        callSites = new HashSet<>();
    }

    @Override
    public boolean equals(Object o){
        if (o==null || o.getClass() != this.getClass()) return false;

        return allocSite.equals(((abstractObj)o).allocSite);
    }

    @Override
    public int hashCode(){
        return allocSite.hashCode();
    }

    @Override
    public String toString(){
        return "Obj@"+allocSite.getJavaSourceStartLineNumber();
    }
};

class dummyObj extends abstractObj{
    static int NumParams;
    int paramId;

    public dummyObj(){
        super(null);
        paramId = NumParams;
        NumParams +=1;
    }

    @Override
    public boolean equals(Object o){
        if (o==null || o.getClass() != this.getClass()) return false;

        return paramId==(((dummyObj)o).paramId);
    }

    @Override
    public int hashCode(){
        return Integer.hashCode(paramId);
    }

    @Override
    public String toString(){
        return "DummyObj["+paramId+"]";
    }
}

class nullObj extends abstractObj {
    public static final nullObj INSTANCE = new nullObj();

    public nullObj(){
        super(null);
    }

    @Override
    public boolean equals(Object o){
        if (!(o instanceof nullObj)) return false;
        return true;
    }

    @Override
    public int hashCode(){
        return 1029;
    }

    @Override
    public String toString(){
        return "NullObj";
    }
};


class Pair<A,B> {
    final public A first;
    final public B second;

    public Pair(A a, B b){
        first = a;
        second = b;
    }

    @Override
    public boolean equals(Object o){
        if (!(o instanceof Pair)) return false;
        Pair<?,?> p = (Pair<?,?>)o;
        return first.equals(p.first) && second.equals(p.second);
    }

    @Override
    public int hashCode(){
        return first.hashCode() * 31 + second.hashCode();
    }
};

class stackHeapState {
    Map<Local, Set<abstractObj>> stack = new HashMap<>();
    Map<Pair<abstractObj,SootField>, Set<abstractObj>> heap = new HashMap<>();
    Set<Local> canBeUninit = new HashSet<>();

    Set<abstractObj> escaped = new HashSet<>();
    Set<abstractObj> haveBeenModified = new HashSet<>();

    public stackHeapState() {}

    public stackHeapState(stackHeapState other){
        for (var e: other.stack.entrySet()){
            stack.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        for (var e: other.heap.entrySet()){
            heap.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        for (var e: other.canBeUninit){
            canBeUninit.add(e);
        }
        for (var e: other.escaped){
            escaped.add(e);
        }
        for (var e: other.haveBeenModified){
            haveBeenModified.add(e);
        }
    }

    @Override
    public boolean equals(Object obj){
        if (!(obj instanceof stackHeapState)) return false;

        stackHeapState other = (stackHeapState) obj;
        return stack.equals(other.stack) && heap.equals(other.heap) && canBeUninit.equals(other.canBeUninit) && escaped.equals(other.escaped) && haveBeenModified.equals(other.haveBeenModified);
    }

    @Override
    public int hashCode(){
        return 1011201* haveBeenModified.hashCode() + 10211 * canBeUninit.hashCode() + 1009* stack.hashCode() + 31 * heap.hashCode() + escaped.hashCode();
    }
};

class StackHeapAnalysis extends ForwardFlowAnalysis<Unit, stackHeapState> {
    Map<Unit, abstractObj> allocObjects = new HashMap<>();
    Map<Integer, dummyObj> argToInitDummyObj = new HashMap<>();
    
    Set<Local> funcLocals = new HashSet<>();
    Set<abstractObj> modifiedInsideFuncCalls = new HashSet<>();
    // Map<abstractObj, Set<Integer>> objToCallSites = new HashMap<>();

    ExceptionalUnitGraph cfg;
    Body body;
    CallGraph cg; 
    Map<SootMethod, Set<Integer>> methodEscapesParam;
    Map<SootMethod, Set<Integer>> methodWritesToParam;
    Map<SootMethod, Map<Integer, Set<Integer>>> methodParamCallSites;
    Map<Integer, String> toPrint;

    public StackHeapAnalysis(ExceptionalUnitGraph cfg, Body body, CallGraph cg, Map<SootMethod, Set<Integer>> methodEscapesParam, Map<SootMethod, Set<Integer>> methodWritesToParam, Map<SootMethod, Map<Integer, Set<Integer>>> methodParamCallSites, Map<Integer, String> toPrint){
        super(cfg);
        funcLocals.addAll(body.getLocals());

        this.cfg = cfg;
        this.body = body;
        this.cg = cg;
        this.methodEscapesParam = methodEscapesParam;
        this.methodWritesToParam = methodWritesToParam;
        this.methodParamCallSites = methodParamCallSites;
        this.toPrint = toPrint;

        doAnalysis();
    }

    @Override
    protected stackHeapState newInitialFlow(){
        return new stackHeapState();
    }

    @Override
    protected stackHeapState entryInitialFlow(){
        stackHeapState s = new stackHeapState();

        // need to make dummy objs for param
        if (!body.getMethod().isStatic()){
            Local thisLocal = body.getThisLocal();
            dummyObj thisDummyObj = new dummyObj();
            s.stack.put(thisLocal, Set.of(thisDummyObj));
            argToInitDummyObj.put(0, thisDummyObj);
        }

        var paramList = body.getParameterLocals();
        for (int i=0; i<paramList.size(); i++){
            Local l = paramList.get(i);
            dummyObj a = new dummyObj();
            s.stack.put(l, Set.of(a));
            argToInitDummyObj.put(i+1, a);
        }

        for (var local: funcLocals){
            s.canBeUninit.add(local);
        }
        return s;
    }

    @Override
    protected void copy(stackHeapState src, stackHeapState dest){
        dest.stack.clear();
        dest.heap.clear();
        dest.canBeUninit.clear();
        dest.escaped.clear();
        dest.haveBeenModified.clear();

        dest.stack.putAll(new stackHeapState(src).stack);
        dest.heap.putAll(new stackHeapState(src).heap);
        dest.canBeUninit.addAll(new stackHeapState(src).canBeUninit);
        dest.escaped.addAll(new stackHeapState(src).escaped);
        dest.haveBeenModified.addAll(new stackHeapState(src).haveBeenModified);
    }

    @Override
    protected void merge(stackHeapState in1, stackHeapState in2, stackHeapState out){
        out.stack.clear();
        out.heap.clear();
        out.canBeUninit.clear();
        out.escaped.clear();
        out.haveBeenModified.clear();

        Set<Local> stackKeys = new HashSet<>();
        stackKeys.addAll(in1.stack.keySet());
        stackKeys.addAll(in2.stack.keySet());

        for (Local l: stackKeys){
            Set<abstractObj> union = new HashSet<>();
            union.addAll(in1.stack.getOrDefault(l, new HashSet<>()));
            union.addAll(in2.stack.getOrDefault(l, new HashSet<>()));
            
            out.stack.put(l, union);
        }

        Set<Pair<abstractObj,SootField>> heapKeys  = new HashSet<>();
        heapKeys.addAll(in1.heap.keySet());
        heapKeys.addAll(in2.heap.keySet());

        for (var l: heapKeys){
            Set<abstractObj> union = new HashSet<>();

            union.addAll(in1.heap.getOrDefault(l, Set.of(nullObj.INSTANCE)));
            union.addAll(in2.heap.getOrDefault(l, Set.of(nullObj.INSTANCE)));
            
            out.heap.put(l, union);
        }

        for (var l: in1.canBeUninit){
            out.canBeUninit.add(l);
        }
        for (var l: in2.canBeUninit){
            out.canBeUninit.add(l);
        }

        for (var l: in1.escaped){
            out.escaped.add(l);
        }
        for (var l: in2.escaped){
            out.escaped.add(l);
        }

        for (var l: in1.haveBeenModified){
            out.haveBeenModified.add(l);
        }
        for (var l: in2.haveBeenModified){
            out.haveBeenModified.add(l);
        }
    }

    @Override
    protected void flowThrough(stackHeapState in, Unit unit, stackHeapState out){
        copy(in, out);

        Stmt stmt = (Stmt) unit;

        if (stmt.containsInvokeExpr()) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            SootMethod target = invoke.getMethod();

            if (target.getName().equals("<init>")) return;

            Iterator<Edge> targets = cg.edgesOutOf(stmt);
            
            Set<Integer> paramsCanEscape = new HashSet<>();
            Set<Integer> paramsCanBeModified = new HashSet<>();
            Map<Integer,Set<Integer>> paramToCallSite = new HashMap<>();

            while (targets.hasNext()) {
                Edge edge = targets.next();
                SootMethod targetMethod = edge.tgt();
                paramsCanEscape.addAll(methodEscapesParam.getOrDefault(targetMethod, new HashSet<>()));
                paramsCanBeModified.addAll(methodWritesToParam.getOrDefault(targetMethod, new HashSet<>()));

                for (int i=0; i<=invoke.getArgCount(); i++){
                    paramToCallSite.computeIfAbsent(i, k -> new HashSet<>()).addAll(methodParamCallSites.getOrDefault(targetMethod, new HashMap<>()).getOrDefault(i, new HashSet<>()));
                }
            }
            
            if (invoke instanceof InstanceInvokeExpr){
                Value base = ((InstanceInvokeExpr) invoke).getBase();

                Set<abstractObj> pts = in.stack.getOrDefault(base, new HashSet<>());
                for (var obj: pts) {
                    obj.callSites.add(stmt.getJavaSourceStartLineNumber());
                    obj.callSites.addAll(paramToCallSite.get(0));
                    // objToCallSites.computeIfAbsent(obj, k -> new HashSet<>()).add(stmt.getJavaSourceStartLineNumber());

                    // objToCallSites.computeIfAbsent(obj, k -> new HashSet<>()).addAll(paramToCallSite.getOrDefault(0, new HashSet<>()));
                }

                if (paramsCanEscape.contains(0)) for (var obj: pts) markEscaped(obj, out);
                if (paramsCanBeModified.contains(0)) {
                    out.haveBeenModified.addAll(pts);
                    modifiedInsideFuncCalls.addAll(pts);
                }
            }
                
            var argList = invoke.getArgs();
            for (int i=0; i<argList.size(); i++){
                var arg = argList.get(i);
                if (arg instanceof Local) {
                    Set<abstractObj> pts = in.stack.getOrDefault(arg, new HashSet<>());
                    for (var obj: pts) {
                        obj.callSites.add(stmt.getJavaSourceStartLineNumber());
                        obj.callSites.addAll(paramToCallSite.get(i+1));
                        // objToCallSites.computeIfAbsent(obj, k -> new HashSet<>()).add(stmt.getJavaSourceStartLineNumber());
                        // objToCallSites.computeIfAbsent(obj, k -> new HashSet<>()).addAll(paramToCallSite.getOrDefault(i+1, new HashSet<>()));
                    }

                    if (paramsCanEscape.contains(i+1)) for (var obj: pts) markEscaped(obj, out);
                    if (paramsCanBeModified.contains(i+1)) {
                        out.haveBeenModified.addAll(pts);
                        modifiedInsideFuncCalls.addAll(pts);
                    }
                }
            }
            return;
        }

        if (stmt instanceof ReturnStmt) {
            ReturnStmt returnStmt = (ReturnStmt) stmt;
            Local retLocal = (Local) returnStmt.getOp();

            Set<abstractObj> pts = in.stack.getOrDefault(retLocal, new HashSet<>());
            for (var obj: pts) markEscaped(obj, out);
        }

        if (!(stmt instanceof AssignStmt)) {
            // System.err.println("WHAT JUST HAPPENED? "+stmt);
            return;
        }

        AssignStmt as = (AssignStmt) stmt;

        Value lhs = as.getLeftOp();
        Value rhs = as.getRightOp();

        if (lhs instanceof Local){
            // out.haveBeenModified.addAll(in.stack.get((Local) lhs));

            if (rhs instanceof NullConstant){
                out.stack.put((Local)lhs, Set.of(nullObj.INSTANCE));
                return;
            }

            /* copy of ref vars */
            if (rhs instanceof Local){
                Set<abstractObj> pts = out.stack.getOrDefault(rhs, new HashSet<>());

                out.stack.put((Local)lhs, new HashSet<>(pts));
                out.canBeUninit.remove(lhs);
                return;
            }

            /* constant value */
            if (rhs instanceof Constant){
                abstractObj obj = allocObjects.computeIfAbsent(unit, u->new abstractObj(u));

                out.stack.put((Local)lhs, Set.of(obj));
                out.canBeUninit.remove(lhs);
                return;
            }

            /* New obj alloc */
            if (rhs instanceof AnyNewExpr){
                abstractObj obj = allocObjects.computeIfAbsent(unit, u->new abstractObj(u));

                out.stack.put((Local)lhs, Set.of(obj));
                out.canBeUninit.remove(lhs);
                return;
            }

            /* load x = y.f */
            if (rhs instanceof InstanceFieldRef) {
                InstanceFieldRef fr = (InstanceFieldRef) rhs;

                Local base = (Local) fr.getBase();
                SootField field = fr.getField();
                
                Set<abstractObj> result = new HashSet<>();

                Set<abstractObj> baseObjs = out.stack.getOrDefault(base, new HashSet<>());

                for (abstractObj o: baseObjs){
                    if (o instanceof nullObj) continue;

                    Pair<abstractObj,SootField> key = new Pair<>(o, field);
                    result.addAll(out.heap.getOrDefault(key, Set.of(nullObj.INSTANCE)));
                }

                out.stack.put((Local)lhs, result);
                out.canBeUninit.remove(lhs);
                return;
            }
        }

        /* field store x.f = y */
        if (lhs instanceof InstanceFieldRef){
            InstanceFieldRef fr = (InstanceFieldRef) lhs;
            Local base = (Local) fr.getBase();
            SootField field = fr.getField();

            Set<abstractObj> result = new HashSet<>();

            if (rhs instanceof Local){
                result.addAll(in.stack.getOrDefault((Local) rhs, new HashSet<>()));
            } else if (rhs instanceof Constant){
                result.add(new abstractObj(unit));
            }

            Set<abstractObj> baseObjs = in.stack.getOrDefault(base, new HashSet<>());
            out.haveBeenModified.addAll(baseObjs);

            if (baseObjs.size() == 1 && !in.canBeUninit.contains(base)){
                // can do strong updates here
                abstractObj o = baseObjs.iterator().next();
                if (!(o instanceof nullObj)){
                    Pair<abstractObj, SootField> key = new Pair<>(o, field);
                    Set<abstractObj> newVal = new HashSet<>(result);
                    out.heap.put(key, newVal);

                    if (in.escaped.contains(o)) {
                        for (var res: result) markEscaped(res, out);
                    }
                }
            } else {
                // weak update
                for (abstractObj o: baseObjs){
                    if (o instanceof nullObj) continue;

                    Pair<abstractObj, SootField> key = new Pair<>(o, field);

                    out.heap.computeIfAbsent(key, k->new HashSet<>()).addAll(result);

                    if (in.escaped.contains(o)) {
                        for (var res: result) markEscaped(res, out);
                    }
                }
            }

            return; 
        }                       

        if (lhs instanceof StaticFieldRef){
            System.out.println("Reached here "+ lhs);
            Set<abstractObj> pts = in.stack.getOrDefault(rhs, new HashSet<>());
            System.out.println("Reached here "+ pts);
            for (var obj: pts) markEscaped(obj, out);
        }
        
        // System.err.println("WHAT UNGODLY STATEMENT APPEARED HERE?");

        return;
    }

    void markEscaped(abstractObj obj, stackHeapState out){
        boolean added = out.escaped.add(obj);

        if (!added) return;

        for (var hp: out.heap.keySet()){
            if (hp.first.equals(obj)) {
                for (var ho: out.heap.getOrDefault(hp, new HashSet<>())) markChildrenEscaped(ho, out);
            }
        }
        return;
    }

    void markChildrenEscaped(abstractObj obj, stackHeapState out){
        for (var hp: out.heap.keySet()){
            if (hp.first.equals(obj)) {
                for (var ho: out.heap.getOrDefault(hp, new HashSet<>())) markEscaped(ho, out);
            }
        }
        return;
    }

    public void printScalarReplObjs(){
        UnitGraph cfg = new ExceptionalUnitGraph(body);
        Unit exitUnit = cfg.getTails().get(0);

        // TODO: extend for multiple exitUnits
        stackHeapState finalState = getFlowAfter(exitUnit);

        List<String> lines = new ArrayList<>();

        for (Unit u: body.getUnits()){
            if (!(u instanceof AssignStmt) || !(((AssignStmt) u).getRightOp() instanceof AnyNewExpr)) continue;
            AnyNewExpr newExpr = (AnyNewExpr)((AssignStmt) u).getRightOp();

            abstractObj obj = allocObjects.get(u);

            boolean escapes = finalState.escaped.contains(obj);
            boolean modifiedInCall = modifiedInsideFuncCalls.contains(obj);

            boolean scalarReplacable = !escapes && !modifiedInCall;

            String output = "O"+u.getJavaSourceStartLineNumber()+" = ";
            if (scalarReplacable) output += "Y"+obj.callSites;
            else output += "N";
            
            toPrint.put(u.getJavaSourceStartLineNumber(), output);
        }
    }

    public void printDebug(){
        for (Unit u: cfg){
            stackHeapState in = getFlowBefore(u);
            System.err.println("\n");
            System.err.println("--BEFORE for statement no. "+u.getJavaSourceStartLineNumber()+ ": "+ u);

            System.err.println("-STACK-");
            for (var e: in.stack.entrySet()){
                System.err.println(" "+e.getKey()+"->"+e.getValue());
            }

            System.err.println("-HEAP-");
            for (var e: in.heap.entrySet()){
                Pair<abstractObj, SootField> key = e.getKey();

                System.err.println(" ("+key.first+"."+key.second.getName()+")->"+e.getValue());
            }

            System.err.println("-UNINIT- "+ in.canBeUninit);
            System.err.println("-Escaped- "+in.escaped);
            System.err.println("-Modified- "+in.haveBeenModified);
        }
        System.err.println("List of Objs Modified in Func Calls: "+ modifiedInsideFuncCalls);
        System.err.print("\n\n");
    }
}

public class AnalysisTransformer extends SceneTransformer {
    static Map<SootMethod, Set<Integer>> methodEscapesParam = new HashMap<>();
    static Map<SootMethod, Set<Integer>> methodWritesToParam = new HashMap<>();
    static Map<SootMethod, Map<Integer, Set<Integer>>> methodParamCallSites = new HashMap<>();

    static CallGraph cg;

    static Map<Integer, String> toPrint = new HashMap<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        // Store the call graph once as a static field...
        cg = Scene.v().getCallGraph();

        // This code lets us get the main method, our testcases will only have one start point that is the main method
        // in the Test class...
        var entrypoints = Scene.v().getEntryPoints();
        assert(entrypoints.size() == 1);
        SootMethod entryMethod = entrypoints.get(0);
        
        handleMainMethod(entryMethod);
    }

    void handleMainMethod(SootMethod myMethod) {
        handleMethod(myMethod);

        List<Integer> objs = new ArrayList<>(toPrint.keySet());
        Collections.sort(objs);

        for (int objNo: objs){
            System.out.println(toPrint.get(objNo));
        }
    }

    void handleMethod(SootMethod myMethod) {
        if (!myMethod.isConcrete() || myMethod.isJavaLibraryMethod()) return;

        // return if already analysed
        if (methodEscapesParam.containsKey(myMethod)) return;

        // Get the body
        Body body = myMethod.getActiveBody();
        
        // Iterate over statements
        for (Unit u : body.getUnits()) {
            Stmt stmt = (Stmt) u;
            int lineNumber = stmt.getJavaSourceStartLineNumber();
            
            // If a statement contains a call expression, analyse its call targets. (reverse toposort)
            if (stmt.containsInvokeExpr()) {
                // System.out.println("Call site found: " + stmt + "@" + lineNumber);
                Iterator<Edge> targets = cg.edgesOutOf(stmt);
                
                while (targets.hasNext()) {
                    Edge edge = targets.next();
                    SootMethod targetMethod = edge.tgt();
                    // System.out.println("  -> Potential target: " + targetMethod.getSignature());
                    handleMethod(targetMethod);
                }
            }
        }
        ExceptionalUnitGraph cfg = new ExceptionalUnitGraph(body);

        StackHeapAnalysis ptaAnalysis = new StackHeapAnalysis(cfg, body, cg, methodEscapesParam, methodWritesToParam, methodParamCallSites, toPrint);
        
        // TODO: merge all tails
        Unit lastUnit = cfg.getTails().get(0); 
        stackHeapState finalState = ptaAnalysis.getFlowAfter(lastUnit);

        Set<Integer> WritesTo = new HashSet<>();
        Set<Integer> Escapes = new HashSet<>();
        Map<Integer, Set<Integer>> paramToCallSites = new HashMap<>();

        // check this (index 0)
        if (!myMethod.isStatic()){
            Local thisLocal = body.getThisLocal();
            if (!Collections.disjoint(finalState.escaped, finalState.stack.getOrDefault(thisLocal, new HashSet<>()))){
                Escapes.add(0);
            };
            if (!Collections.disjoint(finalState.haveBeenModified, finalState.stack.getOrDefault(thisLocal, new HashSet<>()))){
                WritesTo.add(0);
            }

            paramToCallSites.put(0, ptaAnalysis.argToInitDummyObj.get(0).callSites);
        }

        // check params
        for (int i=0; i< myMethod.getParameterCount(); i++){
            Local param = body.getParameterLocal(i);

            if (!Collections.disjoint(finalState.escaped, finalState.stack.get(param))){
                Escapes.add(i+1);
            };
            if (!Collections.disjoint(finalState.haveBeenModified, finalState.stack.get(param))){
                WritesTo.add(i+1);
            }
            
            paramToCallSites.put(i+1, ptaAnalysis.argToInitDummyObj.get(i+1).callSites);
            
        }

        // put call sites of params to list

        methodWritesToParam.put(myMethod, WritesTo);
        methodEscapesParam.put(myMethod, Escapes);
        methodParamCallSites.put(myMethod, paramToCallSites);

        ptaAnalysis.printScalarReplObjs();
    }
}
