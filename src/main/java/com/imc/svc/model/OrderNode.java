package com.imc.svc.model;

public class OrderNode<T> {
  public OrderNode<T> head;
  public OrderNode<T> tail;

  public OrderNode<T> next;
  public OrderNode<T> prev;
  public T data;

  public OrderNode() {}

  public OrderNode(OrderNode<T> next, OrderNode<T> prev, T data) {
    this.next = next;
    this.prev = prev;
    this.data = data;
  }

  public OrderNode(
      OrderNode<T> head, OrderNode<T> tail, OrderNode<T> next, OrderNode<T> prev, T data) {
    this.head = head;
    this.tail = tail;

    this.next = next;
    this.prev = prev;
    this.data = data;
  }
}
