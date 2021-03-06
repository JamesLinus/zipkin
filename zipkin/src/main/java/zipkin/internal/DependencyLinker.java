/**
 * Copyright 2015-2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import zipkin.DependencyLink;
import zipkin.internal.v2.Span;
import zipkin.internal.v2.Span.Kind;

import static java.util.logging.Level.FINE;
import static zipkin.Constants.ERROR;

/**
 * This parses a span tree into dependency links used by Web UI. Ex. http://zipkin/dependency
 *
 * <p>This implementation traverses the tree, and only creates links between {@link Kind#SERVER
 * server} spans. One exception is at the bottom of the trace tree. {@link Kind#CLIENT client} spans
 * that record their {@link Span#remoteEndpoint()} are included, as this accounts for uninstrumented
 * services. Spans with {@link Span#kind()} unset, but {@link Span#remoteEndpoint()} set are treated
 * the same as client spans.
 */
public final class DependencyLinker {
  private final Logger logger;
  private final Map<Pair<String>, Long> callCounts = new LinkedHashMap<>();
  private final Map<Pair<String>, Long> errorCounts = new LinkedHashMap<>();

  public DependencyLinker() {
    this(Logger.getLogger(DependencyLinker.class.getName()));
  }

  DependencyLinker(Logger logger) {
    this.logger = logger;
  }

  /**
   * @param spans spans where all spans have the same trace id
   */
  public DependencyLinker putTrace(Collection<zipkin.Span> spans) {
    if (spans.isEmpty()) return this;

    List<Span> linkSpans = new LinkedList<>();
    for (zipkin.Span s : MergeById.apply(spans)) {
      linkSpans.addAll(V2SpanConverter.fromSpan(s));
    }
    return putTrace(linkSpans.iterator());
  }

  static final Node.MergeFunction<Span> MERGE_RPC = new MergeRpc();

  static final class MergeRpc implements Node.MergeFunction<Span> {
    @Override public Span merge(@Nullable Span left, @Nullable Span right) {
      if (left == null) return right;
      if (right == null) return left;
      if (left.kind() == null) {
        return copyError(left, right);
      }
      if (right.kind() == null) {
        return copyError(right, left);
      }
      Span server = left.kind() == Kind.SERVER ? left : right;
      Span client = left == server ? right : left;
      if (server.remoteServiceName() != null) {
        return copyError(client, server);
      }
      return copyError(client, server).toBuilder().remoteEndpoint(client.localEndpoint()).build();
    }

    static Span copyError(Span maybeError, Span result) {
      if (maybeError.tags().containsKey(ERROR)) {
        return result.toBuilder().putTag(ERROR, maybeError.tags().get(ERROR)).build();
      }
      return result;
    }
  }

  /**
   * @param spans spans where all spans have the same trace id
   */
  public DependencyLinker putTrace(Iterator<Span> spans) {
    if (!spans.hasNext()) return this;

    Span first = spans.next();
    Node.TreeBuilder<Span> builder =
      new Node.TreeBuilder<>(logger, MERGE_RPC, first.traceIdString());
    builder.addNode(first.parentId(), first.id(), first);
    while (spans.hasNext()) {
      Span next = spans.next();
      builder.addNode(next.parentId(), next.id(), next);
    }
    Node<Span> tree = builder.build();

    if (logger.isLoggable(FINE)) logger.fine("traversing trace tree, breadth-first");
    for (Iterator<Node<Span>> i = tree.traverse(); i.hasNext(); ) {
      Node<Span> current = i.next();
      Span currentSpan = current.value();
      if (logger.isLoggable(FINE)) {
        logger.fine("processing " + currentSpan);
      }
      if (current.isSyntheticRootForPartialTree()) {
        logger.fine("skipping synthetic node for broken span tree");
        continue;
      }

      Kind kind = currentSpan.kind();
      if (Kind.CLIENT.equals(kind) && !current.children().isEmpty()) {
        logger.fine("deferring link to rpc child span");
        continue;
      }

      String serviceName = currentSpan.localServiceName();
      String remoteServiceName = currentSpan.remoteServiceName();
      if (kind == null) {
        // Treat unknown type of span as a client span if we know both sides
        if (serviceName != null && remoteServiceName != null) {
          kind = Kind.CLIENT;
        } else {
          logger.fine("non-rpc span; skipping");
          continue;
        }
      }

      String child;
      String parent;
      switch (kind) {
        case SERVER:
        case CONSUMER:
          child = serviceName;
          parent = remoteServiceName;
          if (current == tree) { // we are the root-most span.
            if (parent == null) {
              logger.fine("root's peer is unknown; skipping");
              continue;
            }
          }
          break;
        case CLIENT:
        case PRODUCER:
          parent = serviceName;
          child = remoteServiceName;
          break;
        default:
          logger.fine("unknown kind; skipping");
          continue;
      }

      boolean isError = currentSpan.tags().containsKey(ERROR);
      if (kind == Kind.PRODUCER || kind == Kind.CONSUMER) {
        if (parent == null || child == null) {
          logger.fine("cannot link messaging span to its broker; skipping");
        } else {
          addLink(parent, child, isError);
        }
        continue;
      }

      if (logger.isLoggable(FINE) && parent == null) {
        logger.fine("cannot determine parent, looking for first server ancestor");
      }

      Span rpcAncestor = findRpcAncestor(current);
      String rpcAncestorName;
      if (rpcAncestor != null && (rpcAncestorName = rpcAncestor.localServiceName()) != null) {
        // Some users accidentally put the remote service name on client annotations.
        // Check for this and backfill a link from the nearest remote to that service as necessary.
        if (kind == Kind.CLIENT && serviceName != null && !rpcAncestorName.equals(serviceName)) {
          logger.fine("detected missing link to client span");
          addLink(rpcAncestorName, serviceName, false); // we don't know if there's an error here
        }

        // Local spans may be between the current node and its remote parent
        if (parent == null) parent = rpcAncestorName;

        // When an RPC is split between spans, we skip the child (server side). If our parent is a
        // client, we need to check it for errors.
        if (!isError && Kind.CLIENT.equals(rpcAncestor.kind()) &&
          currentSpan.parentId() != null && currentSpan.parentId() == rpcAncestor.id()) {
          isError = rpcAncestor.tags().containsKey(ERROR);
        }
      }

      if (parent == null || child == null) {
        logger.fine("cannot find server ancestor; skipping");
        continue;
      }

      addLink(parent, child, isError);
    }
    return this;
  }

  Span findRpcAncestor(Node<Span> current) {
    Node<Span> ancestor = current.parent();
    while (ancestor != null) {
      if (logger.isLoggable(FINE)) {
        logger.fine("processing ancestor " + ancestor.value());
      }
      if (!ancestor.isSyntheticRootForPartialTree()) {
        Span maybeRemote = ancestor.value();
        if (maybeRemote.kind() != null) return maybeRemote;
      }
      ancestor = ancestor.parent();
    }
    return null;
  }

  void addLink(String parent, String child, boolean isError) {
    if (logger.isLoggable(FINE)) {
      logger.fine("incrementing " + (isError ? "error " : "") + "link " + parent + " -> " + child);
    }
    Pair<String> key = Pair.create(parent, child);
    if (callCounts.containsKey(key)) {
      callCounts.put(key, callCounts.get(key) + 1);
    } else {
      callCounts.put(key, 1L);
    }
    if (!isError) return;
    if (errorCounts.containsKey(key)) {
      errorCounts.put(key, errorCounts.get(key) + 1);
    } else {
      errorCounts.put(key, 1L);
    }
  }

  public List<DependencyLink> link() {
    return link(callCounts, errorCounts);
  }

  /** links are merged by mapping to parent/child and summing corresponding links */
  public static List<DependencyLink> merge(Iterable<DependencyLink> in) {
    Map<Pair<String>, Long> callCounts = new LinkedHashMap<>();
    Map<Pair<String>, Long> errorCounts = new LinkedHashMap<>();

    for (DependencyLink link : in) {
      Pair<String> parentChild = Pair.create(link.parent, link.child);
      long callCount = callCounts.containsKey(parentChild) ? callCounts.get(parentChild) : 0L;
      callCount += link.callCount;
      callCounts.put(parentChild, callCount);
      long errorCount = errorCounts.containsKey(parentChild) ? errorCounts.get(parentChild) : 0L;
      errorCount += link.errorCount;
      errorCounts.put(parentChild, errorCount);
    }

    return link(callCounts, errorCounts);
  }

  static List<DependencyLink> link(Map<Pair<String>, Long> callCounts,
    Map<Pair<String>, Long> errorCounts) {
    List<DependencyLink> result = new ArrayList<>(callCounts.size());
    for (Map.Entry<Pair<String>, Long> entry : callCounts.entrySet()) {
      Pair<String> parentChild = entry.getKey();
      result.add(DependencyLink.builder()
        .parent(parentChild._1)
        .child(parentChild._2)
        .callCount(entry.getValue())
        .errorCount(errorCounts.containsKey(parentChild) ? errorCounts.get(parentChild) : 0L)
        .build());
    }
    return result;
  }
}
