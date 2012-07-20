/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */
package com.akiban.server.t3expressions;

import com.akiban.server.error.AkibanInternalException;
import com.akiban.server.error.NoSuchFunctionException;
import com.akiban.server.error.WrongExpressionArityException;
import com.akiban.server.types3.TAggregator;
import com.akiban.server.types3.TCast;
import com.akiban.server.types3.TClass;
import com.akiban.server.types3.TInputSet;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.TPreptimeValue;
import com.akiban.server.types3.texpressions.TValidatedOverload;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class OverloadResolver {

    public static class OverloadResult {
        private TValidatedOverload overload;

        private TInstance pickedInstance;
        public OverloadResult(TValidatedOverload overload, TInstance pickedInstance) {
            this.overload = overload;
            this.pickedInstance = pickedInstance;
        }

        public TValidatedOverload getOverload() {
            return overload;
        }

        public TInstance getPickedInstance() {
            return pickedInstance;
        }

        public TClass getTypeClass(int inputIndex) {
            return overload.inputSetAt(inputIndex).targetType();
        }
    }

    private final T3RegistryService registry;

    public OverloadResolver(T3RegistryService registry) {
        this.registry = registry;
    }

    public TCast getTCast(TInstance source, TInstance target) {
        return registry.cast(source.typeClass(), target.typeClass());
    }

    /**
     * Returns the common of the two types. For either argument, a <tt>null</tt> value is interpreted as any type. At
     * least one of the input TClasses must be non-<tt>null</tt>. If one of the inputs is null, the result is always
     * the other input.
     * @param tClass1 the first type class
     * @param tClass2 the other type class
     * @return the common class, or <tt>null</tt> if none were found
     * @throws IllegalArgumentException if both inputs are <tt>null</tt>
     */
    public TClass commonTClass(TClass tClass1, TClass tClass2) {
        // NOTE:
        // This method shares some concepts with #reduceToMinimalCastGroups, but the two methods seem different enough
        // that they're best implemented without much common code. But this could be an opportunity for refactoring.

        // handle easy cases where one or the other is null
        if (tClass1 == null) {
            if (tClass2 == null)
                throw new IllegalArgumentException("both inputs can't be null");
            return tClass2;
        }
        if (tClass2 == null)
            return tClass1;

        // If they're the same class, this is a really easy question to answer.
        if (tClass1.equals(tClass2))
            return tClass1;

        // Alright, neither is null and they're both different. Try the hard way.
        Set<? extends TClass> t1Targets = registry.stronglyCastableTo(tClass1);
        Set<? extends TClass> t2Targets = registry.stronglyCastableTo(tClass2);

        // TODO: The following is not very efficient -- opportunity for optimization?

        // Sets.intersection works best when the first arg is smaller, so do that.
        Set<? extends TClass> set1, set2;
        if (t1Targets.size() < t2Targets.size()) {
            set1 = t1Targets;
            set2 = t2Targets;
        }
        else {
            set1 = t2Targets;
            set2 = t1Targets;
        }
        Set<? extends TClass> castGroup = Sets.intersection(set1, set2); // N^2 operation number 1

        // The cast group is the set of type classes such that for each element C of castGroup, both tClass1 and tClass2
        // can be strongly cast to C. castGroup is thus the set of common types for { tClass1, tClass2 }. We now need
        // to find the MOST SPECIFIC cast M such that any element of castGroup which is not M can be strongly castable
        // from M.
        if (castGroup.isEmpty())
            throw new OverloadException("no common types found for " + tClass1 + " and " + tClass2);

        // N^2 operation number 2...
        TClass mostSpecific = null;
        for (TClass candidate : castGroup) {
            if (isMostSpecific(candidate, castGroup)) {
                if (mostSpecific == null)
                    mostSpecific = candidate;
                else
                    return null;
            }
        }
        return mostSpecific;
    }

    public OverloadResult get(String name, List<? extends TPreptimeValue> inputs) {
        Collection<? extends TValidatedOverload> namedOverloads = registry.getOverloads(name);
        if (namedOverloads == null || namedOverloads.isEmpty()) {
            throw new NoSuchFunctionException(name);
        }
        if (namedOverloads.size() == 1) {
            return defaultResolution(inputs, namedOverloads);
        } else {
            return inputBasedResolution(inputs, namedOverloads);
        }
    }

    public TAggregator getAggregation(String name, TClass inputType) {
        List<TAggregator> candidates = new ArrayList<TAggregator>(registry.getAggregates(name));
        for (Iterator<TAggregator> iterator = candidates.iterator(); iterator.hasNext(); ) {
            TAggregator candidate = iterator.next();
            TClass expectedInput = candidate.getTypeClass();
            if (expectedInput == null || expectedInput.equals(inputType)) // null means input type is irrelevant
                return candidate;
            if (!isStrong(registry.cast(inputType, expectedInput)))
                iterator.remove();
        }
        // At this point, all of the aggregators can be strongly casted to. Find the most specific one.
        // First, a quick check to see if there is one one or none.
        int nCandidates = candidates.size();
        if (nCandidates == 0)
            throw new OverloadException("no appropriate aggregate found for " + name + "(" + inputType + ")");
        if (nCandidates == 1)
            return candidates.get(0);
        Set<TClass> aggrRequiredTClasses = new HashSet<TClass>(nCandidates);
        for (TAggregator candidate : candidates) {
            TClass aggrRequiredTClass = candidate.getTypeClass();
            boolean added = aggrRequiredTClasses.add(aggrRequiredTClass);
            assert added : "multiple aggregates of " + name + " expect " + aggrRequiredTClass;
        }
        TAggregator result = null;
        for (TAggregator candidate : candidates) {
            TClass aggrRequiredTClass = candidate.getTypeClass();
            if (isMostSpecific(aggrRequiredTClass, aggrRequiredTClasses)) {
                if (result != null)
                    throw new AkibanInternalException("two most-specific aggregates found for "
                            + name + "(" + inputType + ") -- this should not be possible!");
                result = candidate;
            }
        }
        if (result == null)
            throw new OverloadException("no appropriate aggregate found for " + name + "(" + inputType + ")");
        return result;
    }

    private OverloadResult inputBasedResolution(List<? extends TPreptimeValue> inputs,
                                                Collection<? extends TValidatedOverload> namedOverloads) {
        List<TValidatedOverload> candidates = new ArrayList<TValidatedOverload>(namedOverloads.size());
        for (TValidatedOverload overload : namedOverloads) {
            if (isCandidate(overload, inputs)) {
                candidates.add(overload);
            }
        }
        if (candidates.isEmpty())
            throw new OverloadException("no suitable overload found for");
        TValidatedOverload mostSpecific = null;
        if (candidates.size() == 1) {
            mostSpecific = candidates.get(0);
        } else {
            List<List<TValidatedOverload>> groups = reduceToMinimalCastGroups(candidates);
            if (groups.size() == 1 && groups.get(0).size() == 1)
                mostSpecific = groups.get(0).get(0);
            // else: 0 or >1 candidates
            // TODO: Throw or let registry handle it?
        }
        if (mostSpecific == null)
            throw new OverloadException("no suitable overload found for");
        return buildResult(mostSpecific, inputs);
    }

    private OverloadResult defaultResolution(List<? extends TPreptimeValue> inputs,
                                             Collection<? extends TValidatedOverload> namedOverloads) {
        int nInputs = inputs.size();
        TValidatedOverload resolvedOverload = namedOverloads.iterator().next();
        // throwing an exception here isn't strictly required, but it gives the user a more specific error
        if (!resolvedOverload.coversNInputs(nInputs))
            throw new WrongExpressionArityException(resolvedOverload.positionalInputs(), nInputs);
        return buildResult(resolvedOverload, inputs);
    }

    private boolean isMostSpecific(TClass candidate, Set<? extends TClass> castGroup) {
        for (TClass inner : castGroup) {
            if (candidate.equals(inner))
                continue;
            if (!stronglyCastable(candidate, inner)) {
                return false;
            }
        }
        return true;
    }

    private boolean stronglyCastable(TClass source, TClass target) {
        return isStrong(registry.cast(source, target));
    }

    private boolean isCandidate(TValidatedOverload overload, List<? extends TPreptimeValue> inputs) {
        if (!overload.coversNInputs(inputs.size()))
            return false;
        for (int i = 0, inputsSize = inputs.size(); i < inputsSize; i++) {
            TInstance inputInstance = inputs.get(i).instance();
            // allow this input if...
            // ... input's type it NULL or ?
            if (inputInstance == null)       // input
                continue;
            // ... input set takes ANY
            TInputSet inputSet = overload.inputSetAt(i);
            if (inputSet.targetType() == null)
                continue;
            // ... input can be strongly cast to input set
            TCast cast = registry.cast(inputInstance.typeClass(), inputSet.targetType());
            if (cast != null && cast.isAutomatic())
                continue;
            // This input precludes the use of the overload
            return false;
        }
        // no inputs have precluded this overload
        return true;
    }

    private OverloadResult buildResult(TValidatedOverload overload, List<? extends TPreptimeValue> inputs) {
        TInstance pickingInstance = pickingInstance(overload, inputs);
        return new OverloadResult(overload, pickingInstance);
    }

    private TInstance pickingInstance(TValidatedOverload overload, List<? extends TPreptimeValue> inputs) {
        TInputSet pickingSet = overload.pickingInputSet();
        if (pickingSet == null) {
            return null;
        }
        TClass common = null; // TODO change to TInstance, so we can more precisely pick instances
        for (int i = pickingSet.firstPosition(); i >=0 ; i = pickingSet.nextPosition(i+1)) {
            TInstance instance = inputs.get(i).instance();
            if (instance != null) {
                common = commonTClass(common, instance.typeClass());
                if (common == null)
                    throw new OverloadException(overload.overloadName());
            }
        }
        if (pickingSet.coversRemaining()) {
            for (int i = overload.firstVarargInput(), last = inputs.size(); i < last; ++i) {
                TInstance instance = inputs.get(i).instance();
                if (instance != null) {
                    common = commonTClass(common, instance.typeClass());
                    if (common == null)
                        throw new OverloadException(overload.overloadName());
                }
            }
        }
        if (common == null)
            throw new OverloadException(overload.overloadName());
        return common.instance();
    }

    /*
     * Two overloads have SIMILAR INPUT SETS if they
     *   1) have the same number of input sets
     *   2) each input set from one overload covers the same columns as an input set from the other function
     *
     * For any two overloads A and B, if A and B have SIMILAR INPUT SETS, and the target type of each input
     * set Ai can be strongly cast to the target type of Bi, then A is said to be MORE SPECIFIC than A, and B
     * is discarded as a possible overload.
     */
    private List<List<TValidatedOverload>> reduceToMinimalCastGroups(List<TValidatedOverload> candidates) {
        // NOTE:
        // This method shares some concepts with #commonTClass. See that method for a note about possible refactoring
        // opportunities (tl;dr is the two methods don't share code right now, but they might be able to.)
        List<List<TValidatedOverload>> castGroups = new ArrayList<List<TValidatedOverload>>();

        for(TValidatedOverload B : candidates) {
            final int nInputSets = B.inputSets().size();

            // Find the OVERLOAD CAST GROUP
            List<TValidatedOverload> castGroup = null;
            for(List<TValidatedOverload> group : castGroups) {
                // Groups are not empty, can always get first
                TValidatedOverload cur = group.get(0);
                if(cur.inputSets().size() == nInputSets) {
                    boolean matches = true;
                    for(int i = 0; i < nInputSets && matches; ++i) {
                        matches = (cur.inputSetAt(i).positionsLength() == B.inputSetAt(i).positionsLength());
                    }
                    if(matches) {
                        castGroup = group;
                        break;
                    }
                }
            }

            if(castGroup != null) {
                // Found group, check for more specific
                Iterator<TValidatedOverload> it = castGroup.iterator();
                while(it.hasNext()) {
                    TValidatedOverload A = it.next();
                    boolean AtoB = true;
                    boolean BtoA = true;
                    for(int i = 0; i < nInputSets; ++i) {
                        TInputSet Ai = A.inputSetAt(i);
                        TInputSet Bi = B.inputSetAt(i);
                        AtoB &= isStrong(registry.cast(Ai.targetType(), Bi.targetType()));
                        BtoA &= isStrong(registry.cast(Bi.targetType(), Ai.targetType()));
                    }
                    if(AtoB) {
                        // current more specific
                        B = null;
                        break;
                    } else if(BtoA) {
                        // new more specific
                        it.remove();
                    }
                }
                if(B != null) {
                    // No more specific existed or B was most specific
                    castGroup.add(B);
                }
            } else {
                // No matching group, must be in a new group
                castGroup = new ArrayList<TValidatedOverload>(1);
                castGroup.add(B);
                castGroups.add(castGroup);
            }
        }
        return castGroups;
    }

    private static boolean isStrong(TCast cast) {
        return (cast != null) && cast.isAutomatic();
    }

    // TODO replace with InvalidOperationExceptions
    static class OverloadException extends RuntimeException {
        private OverloadException(String message) {
            super(message);
        }
    }
}
